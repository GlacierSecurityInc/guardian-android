package com.glaciersecurity.glaciermessenger.services;



import androidx.annotation.NonNull;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import com.glaciersecurity.glaciermessenger.Config;
import com.glaciersecurity.glaciermessenger.entities.Account;
import com.glaciersecurity.glaciermessenger.entities.Room;
import com.glaciersecurity.glaciermessenger.parser.IqParser;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import com.glaciersecurity.glaciermessenger.xmpp.XmppConnection;

import im.conversations.android.xmpp.model.stanza.Iq;



import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public class ChannelDiscoveryService {

    private final XmppConnectionService service;

    private final Cache<String, List<Room>> cache;

    ChannelDiscoveryService(XmppConnectionService service) {
        this.service = service;
        this.cache = CacheBuilder.newBuilder().expireAfterWrite(5, TimeUnit.MINUTES).build();
    }

    void cleanCache() {
        cache.invalidateAll();
    }

    void discover(
            @NonNull final String query,
            Method method,
            OnChannelSearchResultsFound onChannelSearchResultsFound) {
        final List<Room> result = cache.getIfPresent(key(method, query));
        if (result != null) {
            onChannelSearchResultsFound.onChannelSearchResultsFound(result);
            return;
        }
        if (method == Method.LOCAL_SERVER) {
            discoverChannelsLocalServers(query, onChannelSearchResultsFound);
        }
    }

    private void discoverChannelsLocalServers(
            final String query, final OnChannelSearchResultsFound listener) {
        final Map<Jid, Account> localMucService = getLocalMucServices();
        if (localMucService.isEmpty()) {
            listener.onChannelSearchResultsFound(Collections.emptyList());
            return;
        }
        if (!query.isEmpty()) {
            final List<Room> cached = cache.getIfPresent(key(Method.LOCAL_SERVER, ""));
            if (cached != null) {
                final List<Room> results = copyMatching(cached, query);
                cache.put(key(Method.LOCAL_SERVER, query), results);
                listener.onChannelSearchResultsFound(results);
            }
        }
        final AtomicInteger queriesInFlight = new AtomicInteger();
        final List<Room> rooms = new ArrayList<>();
        for (final Map.Entry<Jid, Account> entry : localMucService.entrySet()) {
            Iq itemsRequest = service.getIqGenerator().queryDiscoItems(entry.getKey());
            queriesInFlight.incrementAndGet();
            final var account = entry.getValue();
            service.sendIqPacket(
                    account,
                    itemsRequest,
                    (itemsResponse) -> {
                        if (itemsResponse.getType() == Iq.Type.RESULT) {
                            final List<Jid> items = IqParser.items(itemsResponse);
                            for (final Jid item : items) {
                                final Iq infoRequest =
                                        service.getIqGenerator().queryDiscoInfo(item);
                                queriesInFlight.incrementAndGet();
                                service.sendIqPacket(
                                        account,
                                        infoRequest,
                                        infoResponse -> {
                                            if (infoResponse.getType()
                                                    == Iq.Type.RESULT) {
                                                final Room room =
                                                        IqParser.parseRoom(infoResponse);
                                                if (room != null) {
                                                    rooms.add(room);
                                                }
                                                if (queriesInFlight.decrementAndGet() <= 0) {
                                                    finishDiscoSearch(rooms, query, listener);
                                                }
                                            } else {
                                                queriesInFlight.decrementAndGet();
                                            }
                                        });
                            }
                        }
                        if (queriesInFlight.decrementAndGet() <= 0) {
                            finishDiscoSearch(rooms, query, listener);
                        }
                    });
        }
    }

    private void finishDiscoSearch(
            List<Room> rooms, String query, OnChannelSearchResultsFound listener) {
        Collections.sort(rooms);
        cache.put(key(Method.LOCAL_SERVER, ""), rooms);
        if (query.isEmpty()) {
            listener.onChannelSearchResultsFound(rooms);
        } else {
            List<Room> results = copyMatching(rooms, query);
            cache.put(key(Method.LOCAL_SERVER, query), results);
            listener.onChannelSearchResultsFound(rooms);
        }
    }

    private static List<Room> copyMatching(List<Room> haystack, String needle) {
        ArrayList<Room> result = new ArrayList<>();
        for (Room room : haystack) {
            if (room.contains(needle)) {
                result.add(room);
            }
        }
        return result;
    }

    private Map<Jid, Account> getLocalMucServices() {
        final HashMap<Jid, Account> localMucServices = new HashMap<>();
        for (Account account : service.getAccounts()) {
            if (account.isEnabled()) {
                final XmppConnection xmppConnection = account.getXmppConnection();
                if (xmppConnection == null) {
                    continue;
                }
                for (final String mucService : xmppConnection.getMucServers()) {
                    Jid jid = Jid.ofEscaped(mucService);
                    if (!localMucServices.containsKey(jid)) {
                        localMucServices.put(jid, account);
                    }
                }
            }
        }
        return localMucServices;
    }

    private static String key(Method method, String query) {
        return String.format("%s\00%s", method, query);
    }

    public interface OnChannelSearchResultsFound {
        void onChannelSearchResultsFound(List<Room> results);
    }

    public enum Method {
        LOCAL_SERVER
    }
}
