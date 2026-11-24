package cz.cvut.pmassistant.archfixture.agent;

import java.util.logging.Logger;

/**
 * Stands in for an agent class that imports a provider SDK: it depends on {@code java.util.logging}, which the
 * test pretends is forbidden. Used only by {@code ArchitectureTest}, and left out of the real analysis.
 */
public class UsesAForbiddenPackage {

	Logger logger() {
		return Logger.getLogger("fixture");
	}
}
