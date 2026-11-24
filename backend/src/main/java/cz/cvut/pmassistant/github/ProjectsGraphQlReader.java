package cz.cvut.pmassistant.github;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import cz.cvut.pmassistant.github.http.GitHubApiException;
import cz.cvut.pmassistant.github.http.GitHubGraphQl;
import cz.cvut.pmassistant.github.http.PaginationException;
import cz.cvut.pmassistant.github.model.IssueState;
import cz.cvut.pmassistant.github.model.Iteration;
import cz.cvut.pmassistant.github.model.ProjectItem;
import cz.cvut.pmassistant.github.model.ProjectRef;
import cz.cvut.pmassistant.github.model.ProjectSummary;

import tools.jackson.databind.JsonNode;

/**
 * {@link GitHubProjectsReader} on top of the GraphQL API (Projects v2 has no REST equivalent worth using).
 * <p>
 * Only boards that belong to an organization are supported: the App cannot see boards of personal accounts.
 * Lists are read with cursor pagination, 100 per page.
 */
public class ProjectsGraphQlReader implements GitHubProjectsReader {

	static final int MAX_PAGES = 100;
	private static final String STATUS_FIELD = "Status";

	private static final String PROJECTS_QUERY = """
			query($login: String!, $cursor: String) {
			  organization(login: $login) {
			    projectsV2(first: 100, after: $cursor) {
			      pageInfo { hasNextPage endCursor }
			      nodes { id number title closed }
			    }
			  }
			}
			""";

	private static final String ITEMS_QUERY = """
			query($login: String!, $number: Int!, $cursor: String) {
			  organization(login: $login) {
			    projectV2(number: $number) {
			      items(first: 100, after: $cursor) {
			        pageInfo { hasNextPage endCursor }
			        nodes {
			          id
			          content {
			            __typename
			            ... on Issue { number title state updatedAt repository { nameWithOwner } }
			          }
			          fieldValues(first: 20) {
			            nodes {
			              __typename
			              ... on ProjectV2ItemFieldSingleSelectValue {
			                name
			                field { ... on ProjectV2FieldCommon { name } }
			              }
			              ... on ProjectV2ItemFieldIterationValue {
			                iterationId
			                field { ... on ProjectV2FieldCommon { name } }
			              }
			            }
			          }
			        }
			      }
			    }
			  }
			}
			""";

	private static final String ITERATIONS_QUERY = """
			query($login: String!, $number: Int!) {
			  organization(login: $login) {
			    projectV2(number: $number) {
			      fields(first: 50) {
			        nodes {
			          __typename
			          ... on ProjectV2IterationField {
			            name
			            configuration {
			              iterations { id title startDate duration }
			              completedIterations { id title startDate duration }
			            }
			          }
			        }
			      }
			    }
			  }
			}
			""";

	private final GitHubGraphQl graphQl;

	public ProjectsGraphQlReader(GitHubGraphQl graphQl) {
		this.graphQl = graphQl;
	}

	/** Open boards of an organization. */
	@Override
	public List<ProjectSummary> listProjects(String ownerLogin) {
		List<ProjectSummary> projects = new ArrayList<>();
		forEachPage(cursor -> {
			Map<String, Object> variables = new HashMap<>();
			variables.put("login", ownerLogin);
			variables.put("cursor", cursor);
			JsonNode page = organization(graphQl.query(PROJECTS_QUERY, variables), ownerLogin).path("projectsV2");
			for (JsonNode node : page.path("nodes")) {
				if (!node.path("closed").asBoolean(false)) {
					projects.add(new ProjectSummary(text(node.get("id")), node.path("number").asInt(),
							text(node.get("title"))));
				}
			}
			return page.path("pageInfo");
		});
		return projects;
	}

	/**
	 * Issues on the board, with the value of the "Status" field and the id of the assigned iteration. Pull
	 * requests and draft items are skipped: they have no issue number to act on.
	 */
	@Override
	public List<ProjectItem> listItems(ProjectRef project) {
		List<ProjectItem> items = new ArrayList<>();
		forEachPage(cursor -> {
			JsonNode page = project(graphQl.query(ITEMS_QUERY, projectVariables(project, cursor)), project)
					.path("items");
			for (JsonNode node : page.path("nodes")) {
				toItem(node).ifPresent(items::add);
			}
			return page.path("pageInfo");
		});
		return items;
	}

	/** Active and completed iterations of the board's (first) iteration field, ordered by start date. */
	@Override
	public List<Iteration> listIterations(ProjectRef project) {
		JsonNode fields = project(graphQl.query(ITERATIONS_QUERY, projectVariables(project, null)), project)
				.path("fields").path("nodes");
		List<Iteration> iterations = new ArrayList<>();
		for (JsonNode field : fields) {
			JsonNode configuration = field.path("configuration");
			if (configuration.isObject()) {
				addIterations(iterations, configuration.path("completedIterations"));
				addIterations(iterations, configuration.path("iterations"));
				break;
			}
		}
		iterations.sort(Comparator.comparing(Iteration::startDate));
		return iterations;
	}

	@Override
	public Optional<Iteration> currentIteration(ProjectRef project, LocalDate today) {
		return listIterations(project).stream().filter(iteration -> iteration.contains(today)).findFirst();
	}

	private static Optional<ProjectItem> toItem(JsonNode node) {
		JsonNode content = node.path("content");
		if (!"Issue".equals(text(content.get("__typename")))) {
			return Optional.empty();
		}
		String status = null;
		String iterationId = null;
		for (JsonNode value : node.path("fieldValues").path("nodes")) {
			String type = text(value.get("__typename"));
			if ("ProjectV2ItemFieldSingleSelectValue".equals(type)
					&& STATUS_FIELD.equalsIgnoreCase(text(value.path("field").get("name")))) {
				status = text(value.get("name"));
			}
			else if ("ProjectV2ItemFieldIterationValue".equals(type) && iterationId == null) {
				iterationId = text(value.get("iterationId"));
			}
		}
		String updatedAt = text(content.get("updatedAt"));
		return Optional.of(new ProjectItem(
				text(node.get("id")),
				content.path("number").asInt(),
				text(content.get("title")),
				IssueState.valueOf(text(content.get("state"))),
				updatedAt == null ? null : Instant.parse(updatedAt),
				text(content.path("repository").get("nameWithOwner")),
				status,
				iterationId));
	}

	private static void addIterations(List<Iteration> target, JsonNode nodes) {
		for (JsonNode node : nodes) {
			target.add(new Iteration(text(node.get("id")), text(node.get("title")),
					LocalDate.parse(text(node.get("startDate"))), node.path("duration").asInt()));
		}
	}

	/** Reads pages until GitHub says there are no more; fails rather than loop or return a partial list. */
	private static void forEachPage(PageReader reader) {
		Set<String> seenCursors = new HashSet<>();
		String cursor = null;
		for (int page = 1;; page++) {
			if (page > MAX_PAGES) {
				throw new PaginationException("More than " + MAX_PAGES + " GraphQL pages");
			}
			JsonNode pageInfo = reader.read(cursor);
			if (!pageInfo.path("hasNextPage").asBoolean(false)) {
				return;
			}
			cursor = text(pageInfo.get("endCursor"));
			if (cursor == null || !seenCursors.add(cursor)) {
				throw new PaginationException("GraphQL pagination does not advance (cursor " + cursor + ")");
			}
		}
	}

	/** Reads one page and returns its {@code pageInfo}. */
	@FunctionalInterface
	private interface PageReader {

		JsonNode read(String cursor);
	}

	private static Map<String, Object> projectVariables(ProjectRef project, String cursor) {
		Map<String, Object> variables = new HashMap<>();
		variables.put("login", project.ownerLogin());
		variables.put("number", project.number());
		if (cursor != null) {
			variables.put("cursor", cursor);
		}
		return variables;
	}

	private static JsonNode organization(JsonNode data, String login) {
		JsonNode organization = data.path("organization");
		if (!organization.isObject()) {
			throw new GitHubApiException.NotFound("organization " + login + " not found", "POST", "/graphql", null);
		}
		return organization;
	}

	private static JsonNode project(JsonNode data, ProjectRef ref) {
		JsonNode project = organization(data, ref.ownerLogin()).path("projectV2");
		if (!project.isObject()) {
			throw new GitHubApiException.NotFound(
					"project " + ref.number() + " of " + ref.ownerLogin() + " not found", "POST", "/graphql", null);
		}
		return project;
	}

	private static String text(JsonNode node) {
		return node == null || node.isMissingNode() || node.isNull() ? null : node.asString();
	}
}
