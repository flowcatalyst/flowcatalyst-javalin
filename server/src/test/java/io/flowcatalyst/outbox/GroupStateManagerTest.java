package io.flowcatalyst.outbox;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/// [GroupStateManager] — the three states and the four transitions (spec §5).
class GroupStateManagerTest {

    @Test
    void aNeverSeenGroupIsActiveAndAbsentFromEveryListing() {
        var m = new GroupStateManager();

        assertThat(m.isActive("g1")).isTrue();
        assertThat(m.groupStates()).as("RUNNING is absence, not an entry").isEmpty();
        assertThat(m.blockedGroups()).isEmpty();
    }

    @Test
    void pauseOnlyAppliesFromRunningOrAbsent() {
        var m = new GroupStateManager();

        assertThat(m.pause("g1")).as("absent -> paused").isTrue();
        assertThat(m.isActive("g1")).isFalse();
        assertThat(m.pause("g1")).as("already paused -> no-op").isFalse();

        m.block("g2", "poison", "boom");
        assertThat(m.pause("g2")).as("blocked -> pause has no effect").isFalse();
        assertThat(m.groupStates()).filteredOn(i -> i.group().equals("g2"))
                .extracting(GroupStateManager.GroupInfo::status).containsExactly("BLOCKED");
    }

    @Test
    void resumeOnlyAppliesFromPaused() {
        var m = new GroupStateManager();

        assertThat(m.resume("never-paused")).as("running -> resume has no effect").isFalse();

        m.pause("g1");
        assertThat(m.resume("g1")).as("paused -> resumed").isTrue();
        assertThat(m.isActive("g1")).isTrue();
        assertThat(m.groupStates()).as("resumed group is RUNNING again — absent").isEmpty();

        m.block("g2", "poison", "boom");
        assertThat(m.resume("g2")).as("blocked -> resume has no effect").isFalse();
    }

    @Test
    void blockIsUnconditionalAndCarriesTheItemIdAndError() {
        var m = new GroupStateManager();

        m.block("g1", "obx-1", "terminal failure");

        assertThat(m.isActive("g1")).isFalse();
        assertThat(m.blockedGroups()).containsExactly(new GroupStateManager.GroupInfo("g1", "BLOCKED", "obx-1", "terminal failure"));
    }

    @Test
    void clearBlockReturnsWhatWasBlockedAndRestoresRunning() {
        var m = new GroupStateManager();

        assertThat(m.clearBlock("never-blocked")).as("not blocked -> empty").isEmpty();

        m.block("g1", "obx-1", "boom");
        var cleared = m.clearBlock("g1");

        assertThat(cleared).contains(new GroupStateManager.GroupState.Blocked("obx-1", "boom"));
        assertThat(m.isActive("g1")).as("cleared -> RUNNING again").isTrue();
        assertThat(m.blockedGroups()).isEmpty();
    }

    @Test
    void clearBlockOnAPausedGroupDoesNothing() {
        var m = new GroupStateManager();
        m.pause("g1");

        assertThat(m.clearBlock("g1")).as("paused, not blocked -> empty, untouched").isEmpty();
        assertThat(m.isActive("g1")).as("still paused").isFalse();
    }

    @Test
    void groupStatesListsEveryTrackedGroupAndBlockedGroupsFiltersToBlockedOnly() {
        var m = new GroupStateManager();
        m.pause("paused-1");
        m.block("blocked-1", "obx-1", "boom");

        assertThat(m.groupStates()).extracting(GroupStateManager.GroupInfo::group)
                .containsExactlyInAnyOrder("paused-1", "blocked-1");
        assertThat(m.blockedGroups()).extracting(GroupStateManager.GroupInfo::group)
                .containsExactly("blocked-1");
    }
}
