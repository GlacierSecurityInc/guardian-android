package com.glaciersecurity.glaciermessenger.entities;

import androidx.annotation.NonNull;

import java.util.Locale;

import com.glaciersecurity.glaciermessenger.R;
import com.glaciersecurity.glaciermessenger.xml.Element;

public class Presence implements Comparable<Presence> {

	public enum Status {
		CHAT, ONLINE, AWAY, XA, DND, OFFLINE;

		public String toShowString() {
            return switch (this) {
                case CHAT -> "chat";
                case AWAY -> "away";
                case XA -> "offline";
                case DND -> "dnd";
                default -> null;
            };
        }

		public int getStatusIcon() {
            return switch (this) {
                case AWAY -> R.drawable.ic_orange;
                case OFFLINE, XA -> R.drawable.ic_grey;
                case DND -> R.drawable.ic_red;
                default -> R.drawable.ic_green;
            };
		}

		public static Status fromShowString(String show) {
			if (show == null) {
				return ONLINE;
			} else {
                return switch (show.toLowerCase(Locale.US)) {
                    case "away" -> AWAY;
					case "xa", "offline" -> XA;
                    case "dnd" -> DND;
                    case "chat" -> CHAT;
                    default -> ONLINE;
                };
			}
		}

		public String toDisplayString() {
			return switch (this) {
				case ONLINE, CHAT -> "Online";
				case AWAY -> "Away";
				case DND -> "Do Not Disturb";
				case XA, OFFLINE -> "Offline";
            };
		}

	}
	public enum StatusMessage {
		IN_MEETING, ON_TRAVEL, OUT_SICK, VACATION, CUSTOM;

		public final static int meetingIcon = 0x1F4C5;
		public final static int travelIcon = 0x1F6EB;
		public final static int sickIcon = 0x1F912;
		public final static int vacationIcon = 0x1F334;
		public final static int customIcon = 0x1F4AC;

		public String toShowString() {
			return switch (this) {
				case IN_MEETING -> getEmojiByUnicode(meetingIcon) + "\tIn a meeting";
				case ON_TRAVEL -> getEmojiByUnicode(travelIcon) + "\tOn travel";
				case OUT_SICK -> getEmojiByUnicode(sickIcon) + "\tOut sick";
				case VACATION -> getEmojiByUnicode(vacationIcon) + "\tVacation";
				default -> "";
			};
		}

		public String toShowIcon() {
			return switch (this) {
				case IN_MEETING -> getEmojiByUnicode(meetingIcon);
				case ON_TRAVEL -> getEmojiByUnicode(travelIcon);
				case OUT_SICK -> getEmojiByUnicode(sickIcon);
				case VACATION -> getEmojiByUnicode(vacationIcon);
				case CUSTOM -> getEmojiByUnicode(customIcon);
			};
		}
	}


	private final Status status;
	private ServiceDiscoveryResult disco;
	private final String ver;
	private final String hash;
	private final String node;
	private final String message;

	private Presence(Status status, String ver, String hash, String node, String message) {
		this.status = status;
		this.ver = ver;
		this.hash = hash;
		this.node = node;
		this.message = message;
	}

	public static Presence parse(String show, Element caps, String message) {
		final String hash = caps == null ? null : caps.getAttribute("hash");
		final String ver = caps == null ? null : caps.getAttribute("ver");
		final String node = caps == null ? null : caps.getAttribute("node");
		return new Presence(Status.fromShowString(show), ver, hash, node, message);
	}

	@Override
	public int compareTo(@NonNull Presence other) {
		return this.status.compareTo(other.status);
	}

	public Status getStatus() {
		return this.status;
	}

	public boolean hasCaps() {
		return ver != null && hash != null;
	}

	public String getVer() {
		return this.ver;
	}

	public String getNode() {
		return this.node;
	}

	public String getHash() {
		return this.hash;
	}

	public String getMessage() {
		return this.message;
	}

	public void setServiceDiscoveryResult(ServiceDiscoveryResult disco) {
		this.disco = disco;
	}

	public ServiceDiscoveryResult getServiceDiscoveryResult() {
		return disco;
	}

	public static String getEmojiByUnicode(int unicode){
		return new String(Character.toChars(unicode));
	}
}
