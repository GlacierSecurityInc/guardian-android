package com.glaciersecurity.glaciermessenger.entities;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.glaciersecurity.glaciermessenger.xml.Element;
import com.glaciersecurity.glaciermessenger.xml.Namespace;
import com.glaciersecurity.glaciermessenger.xmpp.Jid;
import org.junit.Test;

public class BookmarkTest {

    @Test
    public void parsesPasswordFromBookmarks2Item() {
        final Element item = new Element("item");
        item.setAttribute("id", "room@conference.example.com");
        final Element conference = item.addChild("conference", Namespace.BOOKMARKS2);
        conference.setAttribute("autojoin", "true");
        conference.addChild("password").setContent("rotated-secret");

        final Bookmark bookmark = Bookmark.parseFromItem(item, null);

        assertNotNull(bookmark);
        assertEquals(Jid.of("room@conference.example.com"), bookmark.getJid());
        assertEquals("rotated-secret", bookmark.getPassword());
    }
}
