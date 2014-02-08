package multicast;

import ipc.MessagePasser;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;

import utils.ConfigurationParser;
import utils.ConfigurationParser.ConfigInfo;

/**
 * Casual-Ordering Reliable Multicast (CORMulticast). Implementation details:
 * (1) Reliability Tools #1 in the lecture slides (R-multicast and R-deliver on
 * the book, Figure 15-9). (2) Causal ordering using vector timestamps on the
 * book (Figure 15.15).
 * 
 * @author Hao Gao
 * @author Yinsu Chu
 * 
 */
public class CORMulticast {
	private static final String GROUP_NAME = "name";
	private static final String GROUP_MEMBER = "members";

	// each group is represented as a HashMap, inside which key GROUP_NAME has
	// the group's name as a String value, key GROUP_MEMBER has the group's
	// members as an ArrayList<String> value
	private ArrayList<HashMap<String, Object>> groupData;

	// the multicast infrastructure is built upon MessagePasser
	private MessagePasser messagePasser;

	private ArrayList<GroupManager> groups;

	// deliverQueue is shared among all groups
	private LinkedBlockingQueue<MulticastMessage> deliverQueue;

	public CORMulticast(String configurationFileName, String localName,
			ConfigInfo ci, ConfigurationParser cp) {
		this.groupData = ci.getGroups();
		this.messagePasser = new MessagePasser(configurationFileName,
				localName, ci, cp);
		this.groups = new ArrayList<GroupManager>();
		this.deliverQueue = new LinkedBlockingQueue<MulticastMessage>();
		initializeGroups();
	}

	@SuppressWarnings("unchecked")
	private void initializeGroups() {
		int groupId = 0;
		for (HashMap<String, Object> g : groupData) {
			String groupName = (String) g.get(GROUP_NAME);
			ArrayList<String> groupMembers = (ArrayList<String>) (g
					.get(GROUP_MEMBER));
			GroupManager group = new GroupManager(groupId++, groupName,
					groupMembers);
			groups.add(group);
		}
	}

	public void send(MulticastMessage message) {
		// send a message to a group
		for (GroupManager g : groups) {
			g.send(message, this.messagePasser);
		}
	}

	public MulticastMessage receive() {
		// receive a message from deliverQueue
		return null;
	}
}
