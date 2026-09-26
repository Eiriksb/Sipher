package io.github.eiriksb.sipher.server;

import io.github.eiriksb.sipher.server.CaptionRouting.Group;
import io.github.eiriksb.sipher.server.CaptionRouting.GroupType;
import io.github.eiriksb.sipher.server.CaptionRouting.Voice;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** The cases of Simple Voice Chat's routing that {@link CaptionRouting} mirrors. Voice range is 48 blocks. */
class CaptionRoutingTest {
    private static final double RANGE = 48;
    private static final String OVERWORLD = "overworld";
    private static final String NETHER = "the_nether";

    private static final Group NORMAL = new Group(UUID.randomUUID(), GroupType.NORMAL);
    private static final Group OPEN = new Group(UUID.randomUUID(), GroupType.OPEN);
    private static final Group ISOLATED = new Group(UUID.randomUUID(), GroupType.ISOLATED);
    private static final Group OTHER_NORMAL = new Group(UUID.randomUUID(), GroupType.NORMAL);

    private static Voice<String> at(String name, double x) {
        return new Voice<>(name, OVERWORLD, x, 64, 0, null, true);
    }

    private static Voice<String> at(String name, double x, Group group) {
        return new Voice<>(name, OVERWORLD, x, 64, 0, group, true);
    }

    private static Set<String> heardBy(Voice<String> speaker, List<Voice<String>> others, double range) {
        List<Voice<String>> everyone = new ArrayList<>(others);
        everyone.add(speaker);
        return CaptionRouting.listeners(speaker, everyone, range);
    }

    @Test
    void proximityReachesPlayersWithinRangeOnly() {
        Voice<String> speaker = at("speaker", 0);
        assertEquals(Set.of("near", "edge"),
                heardBy(speaker, List.of(at("near", 10), at("edge", 48), at("far", 48.5)), RANGE));
    }

    @Test
    void whisperRangeIsShorter() {
        Voice<String> speaker = at("speaker", 0);
        assertEquals(Set.of("close"), heardBy(speaker, List.of(at("close", 5), at("near", 20)), 8));
    }

    @Test
    void otherDimensionsNeverHearProximity() {
        Voice<String> speaker = at("speaker", 0);
        Voice<String> nether = new Voice<>("nether", NETHER, 0, 64, 0, null, true);
        assertEquals(Set.of(), heardBy(speaker, List.of(nether), RANGE));
    }

    @Test
    void theSpeakerIsNeverAListener() {
        Voice<String> speaker = at("speaker", 0, NORMAL);
        assertEquals(Set.of(), heardBy(speaker, List.of(), RANGE));
    }

    @Test
    void ungroupedSpeakerIsHeardByNearbyNormalAndOpenGroupsButNotIsolatedOnes() {
        Voice<String> speaker = at("speaker", 0);
        assertEquals(Set.of("ungrouped", "normal", "open"), heardBy(speaker, List.of(
                at("ungrouped", 5), at("normal", 5, NORMAL), at("open", 5, OPEN), at("isolated", 5, ISOLATED)), RANGE));
    }

    @Test
    void normalGroupReachesItsMembersEverywhereAndNobodyElse() {
        Voice<String> speaker = at("speaker", 0, NORMAL);
        Voice<String> farMember = new Voice<>("far member", NETHER, 5000, 64, 0, NORMAL, true);
        assertEquals(Set.of("member", "far member"), heardBy(speaker, List.of(
                at("member", 5, NORMAL), farMember, at("ungrouped", 5), at("other group", 5, OTHER_NORMAL)), RANGE));
    }

    @Test
    void isolatedGroupReachesOnlyItsMembers() {
        Voice<String> speaker = at("speaker", 0, ISOLATED);
        assertEquals(Set.of("member"), heardBy(speaker, List.of(at("member", 500, ISOLATED), at("ungrouped", 5)), RANGE));
    }

    @Test
    void openGroupAlsoReachesNearbyPlayersExceptIsolatedGroups() {
        Voice<String> speaker = at("speaker", 0, OPEN);
        assertEquals(Set.of("member", "ungrouped", "other group"), heardBy(speaker, List.of(
                at("member", 500, OPEN), at("ungrouped", 5), at("other group", 5, OTHER_NORMAL),
                at("isolated", 5, ISOLATED), at("far", 100)), RANGE));
    }

    @Test
    void playersWithVoiceChatOffHearNobody() {
        Voice<String> speaker = at("speaker", 0, OPEN);
        Voice<String> deafenedMember = new Voice<>("deafened member", OVERWORLD, 5, 64, 0, OPEN, false);
        Voice<String> deafenedNearby = new Voice<>("deafened nearby", OVERWORLD, 5, 64, 0, null, false);
        assertEquals(Set.of(), heardBy(speaker, List.of(deafenedMember, deafenedNearby), RANGE));
    }

    @Test
    void usesThreeDimensionalDistance() {
        Voice<String> speaker = at("speaker", 0);
        Voice<String> below = new Voice<>("below", OVERWORLD, 30, 64 - 40, 0, null, true); // 50 blocks away
        assertEquals(Set.of(), heardBy(speaker, List.of(below), RANGE));
    }
}
