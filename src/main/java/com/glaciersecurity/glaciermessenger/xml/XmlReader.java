package com.glaciersecurity.glaciermessenger.xml;

import android.util.Xml;

import com.glaciersecurity.glaciermessenger.Config;

import im.conversations.android.xmpp.ExtensionFactory;
import im.conversations.android.xmpp.model.StreamElement;

import org.xmlpull.v1.XmlPullParser;
import org.xmlpull.v1.XmlPullParserException;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;

public class XmlReader implements Closeable {
	// caps recursion in readElement so a maliciously/accidentally deeply
	// nested stanza throws a normal, catchable IOException well before the JVM's native
	// stack limit is reached, instead of risking an uncaught StackOverflowError. Legitimate
	// XMPP stanzas never approach this depth.
	private static final int MAX_ELEMENT_DEPTH = 512;

	private final XmlPullParser parser;
	private InputStream is;

	public XmlReader() {
		this.parser = Xml.newPullParser();
		try {
			this.parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true);
		} catch (XmlPullParserException e) {
		}
	}

	public void setInputStream(InputStream inputStream) throws IOException {
		if (inputStream == null) {
			throw new IOException();
		}
		this.is = inputStream;
		try {
			parser.setInput(new InputStreamReader(this.is));
		} catch (XmlPullParserException e) {
			throw new IOException("error resetting parser");
		}
	}

	public void reset() throws IOException {
		if (this.is == null) {
			throw new IOException();
		}
		try {
			parser.setInput(new InputStreamReader(this.is));
		} catch (XmlPullParserException e) {
			throw new IOException("error resetting parser");
		}
	}

	@Override
	public void close() {
		this.is = null;
	}

	public Tag readTag() throws IOException {
		try {
			while (this.is != null && parser.next() != XmlPullParser.END_DOCUMENT) {
				if (parser.getEventType() == XmlPullParser.START_TAG) {
					Tag tag = Tag.start(parser.getName());
					final String xmlns = parser.getNamespace();
					for (int i = 0; i < parser.getAttributeCount(); ++i) {
						final String prefix = parser.getAttributePrefix(i);
						String name;
						if (prefix != null && !prefix.isEmpty()) {
							name = prefix+":"+parser.getAttributeName(i);
						} else {
							name = parser.getAttributeName(i);
						}
						tag.setAttribute(name,parser.getAttributeValue(i));
					}
					if (xmlns != null) {
						tag.setAttribute("xmlns", xmlns);
					}
					return tag;
				} else if (parser.getEventType() == XmlPullParser.END_TAG) {
					return Tag.end(parser.getName());
				} else if (parser.getEventType() == XmlPullParser.TEXT) {
					return Tag.no(parser.getText());
				}
			}

		} catch (Throwable throwable) {
			throw new IOException("xml parser mishandled "+throwable.getClass().getSimpleName()+"("+throwable.getMessage()+")", throwable);
		}
		return null;
	}

	public <T extends StreamElement> T readElement(final Tag current, final Class<T> clazz)
			throws IOException {
		final Element element = readElement(current);
		if (clazz.isInstance(element)) {
			return clazz.cast(element);
		}
		throw new IOException(
				String.format("Read unexpected {%s}%s", element.getNamespace(), element.getName()));
	}

	public Element readElement(final Tag currentTag) throws IOException {
		return readElement(currentTag, 0);
	}

	private Element readElement(final Tag currentTag, final int depth) throws IOException {
		if (depth > MAX_ELEMENT_DEPTH) {
			throw new IOException("stanza exceeds maximum nesting depth of " + MAX_ELEMENT_DEPTH);
		}
		final var attributes = currentTag.getAttributes();
		final var namespace = attributes.get("xmlns");
		final var name = currentTag.getName();
		final Element element = ExtensionFactory.create(name, namespace);
		element.setAttributes(currentTag.getAttributes());
		Tag nextTag = this.readTag();
		if (nextTag == null) {
			throw new IOException("interrupted mid tag");
		}
		if (nextTag.isNo()) {
			element.setContent(nextTag.getName());
			nextTag = this.readTag();
			if (nextTag == null) {
				throw new IOException("interrupted mid tag");
			}
		}
		while (!nextTag.isEnd(element.getName())) {
			if (!nextTag.isNo()) {
				Element child = this.readElement(nextTag, depth + 1);
				element.addChild(child);
			}
			nextTag = this.readTag();
			if (nextTag == null) {
				throw new IOException("interrupted mid tag");
			}
		}
		return element;
	}
}
