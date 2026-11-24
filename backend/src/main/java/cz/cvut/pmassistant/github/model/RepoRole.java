package cz.cvut.pmassistant.github.model;

/** Built-in repository roles of GitHub, from the weakest to the strongest. */
public enum RepoRole {
	READ, TRIAGE, WRITE, MAINTAIN, ADMIN;

	/** The role for a GitHub role name such as {@code "maintain"}, or null for custom or unknown names. */
	public static RepoRole fromName(String name) {
		if (name == null) {
			return null;
		}
		for (RepoRole role : values()) {
			if (role.name().equalsIgnoreCase(name)) {
				return role;
			}
		}
		return null;
	}
}
