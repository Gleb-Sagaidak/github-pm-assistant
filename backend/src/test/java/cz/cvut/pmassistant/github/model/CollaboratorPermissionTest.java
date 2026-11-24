package cz.cvut.pmassistant.github.model;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import cz.cvut.pmassistant.github.Fixtures;
import tools.jackson.databind.json.JsonMapper;

/** Approval needs "at least triage": the coarse {@code permission} alone cannot answer that. */
class CollaboratorPermissionTest {

	private static CollaboratorPermission of(String permission, String roleName) {
		return new CollaboratorPermission(permission, roleName, null);
	}

	@Test
	void triageIsReportedAsReadByTheCoarseFieldButStillCountsAsTriage() {
		CollaboratorPermission triage = of("read", "triage");

		assertThat(triage.hasAtLeast(RepoRole.TRIAGE)).isTrue();
		assertThat(triage.hasAtLeast(RepoRole.READ)).isTrue();
		assertThat(triage.hasAtLeast(RepoRole.WRITE)).isFalse();
	}

	@Test
	void plainReadIsBelowTriage() {
		assertThat(of("read", "read").hasAtLeast(RepoRole.TRIAGE)).isFalse();
	}

	@Test
	void maintainIsReportedAsWriteButStillCountsAsMaintain() {
		CollaboratorPermission maintain = of("write", "maintain");

		assertThat(maintain.hasAtLeast(RepoRole.MAINTAIN)).isTrue();
		assertThat(maintain.hasAtLeast(RepoRole.ADMIN)).isFalse();
	}

	@Test
	void rolesAreOrderedFromReadToAdmin() {
		for (RepoRole held : RepoRole.values()) {
			CollaboratorPermission user = of(null, held.name().toLowerCase());
			for (RepoRole required : RepoRole.values()) {
				assertThat(user.hasAtLeast(required)).as(held + " vs " + required)
						.isEqualTo(held.compareTo(required) >= 0);
			}
		}
	}

	@Test
	void aCustomRoleIsJudgedByItsCoarsePermission() {
		assertThat(of("write", "release-manager").hasAtLeast(RepoRole.TRIAGE)).isTrue();
		assertThat(of("write", "release-manager").hasAtLeast(RepoRole.MAINTAIN)).isFalse();
		assertThat(of("read", "interns").hasAtLeast(RepoRole.TRIAGE)).as("under-reports, never over-grants").isFalse();
	}

	@Test
	void noneOrMissingValuesGiveNoAccess() {
		assertThat(of("none", "none").hasNoAccess()).isTrue();
		assertThat(of("none", "none").hasAtLeast(RepoRole.READ)).isFalse();
		assertThat(of(null, null).hasNoAccess()).isTrue();
		assertThat(of("read", "read").hasNoAccess()).isFalse();
	}

	@Test
	void roleNamesAreMatchedIgnoringCase() {
		assertThat(of(null, "Maintain").hasAtLeast(RepoRole.MAINTAIN)).isTrue();
	}

	@Test
	void readsGitHubAnswers() throws Exception {
		JsonMapper mapper = JsonMapper.builder().build();

		CollaboratorPermission admin = mapper.readValue(Fixtures.read("permission-admin.json"),
				CollaboratorPermission.class);
		CollaboratorPermission triage = mapper.readValue(Fixtures.read("permission-triage.json"),
				CollaboratorPermission.class);
		CollaboratorPermission read = mapper.readValue(Fixtures.read("permission-read.json"),
				CollaboratorPermission.class);

		assertThat(admin.user().login()).isEqualTo("alice");
		assertThat(admin.hasAtLeast(RepoRole.ADMIN)).isTrue();
		assertThat(triage.permission()).isEqualTo("read");
		assertThat(triage.roleName()).isEqualTo("triage");
		assertThat(triage.hasAtLeast(RepoRole.TRIAGE)).isTrue();
		assertThat(read.hasAtLeast(RepoRole.TRIAGE)).isFalse();
	}
}
