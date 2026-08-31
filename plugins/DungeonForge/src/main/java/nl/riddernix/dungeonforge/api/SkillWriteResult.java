package nl.riddernix.dungeonforge.api;

import java.util.List;
import java.util.Set;

/**
 * What one skill write did.
 *
 * <p>Returned by every mutating skill call so a caller can branch on the
 * outcome instead of guessing. Nothing throws: an unknown node or a player
 * without a class is a {@link SkillWriteStatus}, not an exception.</p>
 *
 * @param status      why it did or did not happen
 * @param classId     the class written to, empty when the call never got that far
 * @param nodes       every node whose level changed, including cascaded revokes
 * @param pointsBefore the player's available points before this call
 * @param points      their available points after it
 */
public record SkillWriteResult(SkillWriteStatus status, String classId, Set<String> nodes,
                               int pointsBefore, int points) {

    public SkillWriteResult {
        nodes = Set.copyOf(nodes);
    }

    /** True for both {@link SkillWriteStatus#SUCCESS} and {@link SkillWriteStatus#UNCHANGED}. */
    public boolean isSuccess() {
        return status == SkillWriteStatus.SUCCESS || status == SkillWriteStatus.UNCHANGED;
    }

    /** Points handed back by a revoke or reset; negative when a call spent them. */
    public int pointsChanged() {
        return points - pointsBefore;
    }

    /** Node ids in a stable order, handy for logging what a cascade took. */
    public List<String> sortedNodes() {
        return nodes.stream().sorted().toList();
    }

    public static SkillWriteResult failed(SkillWriteStatus status, String classId, int points) {
        return new SkillWriteResult(status, classId == null ? "" : classId, Set.of(), points, points);
    }
}
