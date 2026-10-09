package io.github.eiriksb.sipher.server;

import org.jetbrains.annotations.Nullable;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Who hears a speaker on Simple Voice Chat, as plain data so it can be tested without a server. Mirrors Simple Voice
 * Chat's own routing ({@code Server.processGroupPacket}, {@code processProximityPacket} and {@code broadcast}):
 *
 * <ul>
 *     <li>A speaker in a group is heard by every member of that group, in any dimension.</li>
 *     <li>A speaker outside any group, or in an open group, is also heard by players in the same dimension within
 *     range, except members of isolated groups.</li>
 *     <li>Players who have disabled voice chat, or aren't connected to it, hear nobody.</li>
 * </ul>
 */
final class CaptionRouting {
    enum GroupType { NORMAL, OPEN, ISOLATED }

    record Group(UUID id, GroupType type) {
    }

    /**
     * A player as voice chat sees them.
     *
     * @param dimension any value that is equal for players in the same dimension
     * @param group     {@code null} outside a group
     * @param listening connected to voice chat and not disabled
     */
    record Voice<P>(P player, Object dimension, double x, double y, double z, @Nullable Group group, boolean listening) {
        double distanceSquared(Voice<?> other) {
            double dx = x - other.x;
            double dy = y - other.y;
            double dz = z - other.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private CaptionRouting() {
    }

    /** Everyone other than the speaker who hears the speaker, with voice range {@code range} (whisper range while whispering). */
    static <P> Set<P> listeners(Voice<P> speaker, Collection<Voice<P>> players, double range) {
        Set<P> listeners = new LinkedHashSet<>();
        Group group = speaker.group();
        if (group != null) {
            for (Voice<P> other : players) {
                if (other.listening() && other.group() != null && other.group().id().equals(group.id())) {
                    listeners.add(other.player());
                }
            }
        }
        if (group == null || group.type() == GroupType.OPEN) {
            double rangeSquared = range * range;
            for (Voice<P> other : players) {
                if (!other.listening() || !other.dimension().equals(speaker.dimension())
                        || other.distanceSquared(speaker) > rangeSquared) {
                    continue;
                }
                if (other.group() != null && other.group().type() == GroupType.ISOLATED) {
                    continue;
                }
                listeners.add(other.player());
            }
        }
        listeners.remove(speaker.player());
        return listeners;
    }
}
