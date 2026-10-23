package com.glaciersecurity.glaciermessenger.entities;

import com.glaciersecurity.glaciermessenger.utils.Log;

import androidx.annotation.NonNull;

import com.google.common.base.MoreObjects;
import com.google.common.base.Strings;
import com.google.common.collect.Collections2;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;
import com.google.common.collect.Multimaps;
import com.google.common.collect.Ordering;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class Reaction {

    public static final List<String> SUGGESTIONS =
            Arrays.asList(
                    "❤️",
                    "👍",
                    "👎",
                    "😂",
                    "😮",
                    "😢");

    private static final Gson GSON;

    static {
        GSON = new GsonBuilder().registerTypeAdapter(Jid.class, new JidTypeAdapter()).create();
    }

    public final String reaction;
    public final boolean received;
    public final Jid from;
    public final Jid trueJid;
    public final String occupantId;

    public Reaction(
            final String reaction,
            boolean received,
            final Jid from,
            final Jid trueJid,
            final String occupantId) {
        this.reaction = reaction;
        this.received = received;
        this.from = from;
        this.trueJid = trueJid;
        this.occupantId = occupantId;
    }

    public static String toString(final Collection<Reaction> reactions) {
        return (reactions == null || reactions.isEmpty()) ? null : GSON.toJson(reactions);
    }

    public static Collection<Reaction> fromString(final String asString) {
        if (Strings.isNullOrEmpty(asString)) {
            return Collections.emptyList();
        }
        try {
            return sanitize(GSON.fromJson(asString, new TypeToken<List<Reaction>>() {}.getType()));
        } catch (final IllegalArgumentException | JsonSyntaxException e) {
            Log.e(Config.LOGTAG,"could not restore reactions", e);
            return Collections.emptyList();
        }
    }

    public static Collection<Reaction> sanitize(final Collection<Reaction> reactions) {
        if (reactions == null || reactions.isEmpty()) {
            return Collections.emptyList();
        }
        final ImmutableList.Builder<Reaction> builder = new ImmutableList.Builder<>();
        for (final Reaction reaction : reactions) {
            if (reaction != null && reaction.reaction != null) {
                builder.add(reaction);
            }
        }
        return builder.build();
    }

    public static Collection<Reaction> withOccupantId(
            final Collection<Reaction> existing,
            final Collection<String> reactions,
            final boolean received,
            final Jid from,
            final Jid trueJid,
            final String occupantId) {
        final var normalizedOccupantId = Strings.emptyToNull(occupantId);
        final ImmutableList.Builder<Reaction> builder = new ImmutableList.Builder<>();
        for (final Reaction reaction : existing) {
            final boolean sameOwner;
            if (normalizedOccupantId != null) {
                // Room supports XEP-0421: match on occupant-id
                sameOwner = normalizedOccupantId.equals(reaction.occupantId);
            } else {
                // No occupant-id support: avoid wiping all null-occupantId reactions;
                // fall back to full JID comparison so only this sender's reactions are replaced
                sameOwner = from != null && from.equals(reaction.from);
            }
            if (!sameOwner) {
                builder.add(reaction);
            }
        }
        // Deduplicate per XEP-0444: a <reactions> payload must not repeat the same emoji
        final Set<String> seen = new HashSet<>();
        for (final String reactionValue : reactions) {
            if (reactionValue != null && seen.add(reactionValue)) {
                builder.add(new Reaction(reactionValue, received, from, trueJid, normalizedOccupantId));
            }
        }
        return builder.build();
    }

    @NonNull
    @Override
    public String toString() {
        return MoreObjects.toStringHelper(this)
                .add("reaction", reaction)
                .add("received", received)
                .add("from", from)
                .add("trueJid", trueJid)
                .add("occupantId", occupantId)
                .toString();
    }

    public static Collection<Reaction> withFrom(
            final Collection<Reaction> existing,
            final Collection<String> reactions,
            final boolean received,
            final Jid from) {
        return withFrom(existing, reactions, received, from, true);
    }

    public static Collection<Reaction> withFrom(
            final Collection<Reaction> existing,
            final Collection<String> reactions,
            final boolean received,
            final Jid from,
            final boolean matchOnBareJid) {
        final ImmutableList.Builder<Reaction> builder = new ImmutableList.Builder<>();
        // Replace semantics: filter out existing reactions from this sender, then add new ones
        for (final Reaction reaction : existing) {
            final Jid existingFrom = reaction.from;
            final boolean sameFrom;
            if (from == null) {
                sameFrom = existingFrom == null;
            } else if (existingFrom != null) {
                sameFrom = matchOnBareJid
                        ? from.asBareJid().equals(existingFrom.asBareJid())
                        : from.equals(existingFrom);
            } else {
                sameFrom = false;
            }
            if (!sameFrom) {
                builder.add(reaction);
            }
        }
        // Deduplicate per XEP-0444: a <reactions> payload must not repeat the same emoji
        final Set<String> seen = new HashSet<>();
        for (final String reactionValue : Collections2.filter(reactions, Objects::nonNull)) {
            if (seen.add(reactionValue)) {
                builder.add(new Reaction(reactionValue, received, from, null, null));
            }
        }
        return builder.build();
    }

    private static class JidTypeAdapter extends TypeAdapter<Jid> {
        @Override
        public void write(final JsonWriter out, final Jid value) throws IOException {
            if (value == null) {
                out.nullValue();
            } else {
                out.value(value.toEscapedString());
            }
        }

        @Override
        public Jid read(final JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull();
                return null;
            } else if (in.peek() == JsonToken.STRING) {
                final String value = in.nextString();
                return Jid.ofEscaped(value);
            }
            throw new IOException("Unexpected token");
        }
    }

    public static Aggregated aggregated(final Collection<Reaction> reactions) {
        final Collection<Reaction> sanitizedReactions = sanitize(reactions);
        final Map<String, Integer> aggregatedReactions =
                Maps.transformValues(
                        Multimaps.index(sanitizedReactions, r -> r.reaction).asMap(), Collection::size);
        final List<Map.Entry<String, Integer>> sortedList =
                Ordering.from(
                                Comparator.comparingInt(
                                        (Map.Entry<String, Integer> o) -> o.getValue()))
                        .reverse()
                        .immutableSortedCopy(aggregatedReactions.entrySet());
        return new Aggregated(
                sortedList,
                ImmutableSet.copyOf(
                        Collections2.transform(
                                Collections2.filter(sanitizedReactions, r -> !r.received),
                                r -> r.reaction)));
    }

    public static final class Aggregated {

        public final List<Map.Entry<String, Integer>> reactions;
        public final Set<String> ourReactions;

        private Aggregated(
                final List<Map.Entry<String, Integer>> reactions, Set<String> ourReactions) {
            this.reactions = reactions;
            this.ourReactions = ourReactions;
        }
    }
}
