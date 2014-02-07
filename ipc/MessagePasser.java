package ipc;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

import utils.ConfigurationParser;
import utils.ConfigurationParser.ConfigInfo;
import clock.ClockService;
import clock.TimeStamp;

/**
 * This class spawns a thread for each node to receive messages and create a
 * socket to the remote end when sending messages. The receiving thread will
 * keep running and socket created for sending will be saved for later use.
 * 
 * @author Hao Gao
 * @author Yinsu Chu
 * 
 */
public class MessagePasser {

	// check configuration file changes every ten seconds
	private static final int CONFIG_CHANGE_CHECK_INTERVAL = 10000;

	// constants in rule checking
	private static final String RULE_ACTION = "action";
	private static final String RULE_SRC = "src";
	private static final String RULE_DST = "dest";
	private static final String RULE_KIND = "kind";
	private static final String RULE_SEQ_NUM = "seqNum";
	private static final String RULE_DUP = "dupe";
	private static final String ACTION_DROP = "drop";
	private static final String ACTION_DUPLICATE = "duplicate";
	private static final String ACTION_DELAY = "delay";

	private String localName;
	private String localConfigurationFileName;

	private LinkedBlockingQueue<Message> sendBuffer;
	private LinkedBlockingQueue<Message> receiveBuffer;

	// maps from remote node names to their contact information (IP and port)
	private HashMap<String, Contact> contactMap;

	// maps from remote node names to sockets
	private HashMap<String, Socket> socketMap;

	// a global sequence number
	private int seqNum;

	private ClockService clockService;

	private ReentrantLock rulesLock;
	private ArrayList<HashMap<String, Object>> sendRules;
	private ArrayList<HashMap<String, Object>> receiveRules;

	private Watcher watcher;
	private Sender sender;
	private Receiver receiver;

	private Thread watcherThread;
	private Thread senderThread;
	private Thread receiverThread;

	private ServerSocket serverSocket;

	/**
	 * This class periodically checks the configuration file changes.
	 * 
	 * @author Hao Gao
	 * @author Yinsu Chu
	 * 
	 */
	private class Watcher implements Runnable {
		private static final String TEMP_CONFIG_SUFFIX = ".new";
		private ConfigurationParser cp;

		public Watcher() {
			this.cp = new ConfigurationParser();
		}

		public void run() {
			while (true) {
				try {
					Thread.sleep(CONFIG_CHANGE_CHECK_INTERVAL);
				} catch (InterruptedException ex) {
				}
				if (cp.downloadConfigurationFile(localConfigurationFileName
						+ TEMP_CONFIG_SUFFIX)) {
					System.out.println("new configuration file detected");
					File localConfigFile = new File(localConfigurationFileName);
					localConfigFile.delete();
					File tempConfigFile = new File(localConfigurationFileName
							+ TEMP_CONFIG_SUFFIX);
					tempConfigFile.renameTo(localConfigFile);
					ConfigInfo ci = cp.yamlExtraction(
							localConfigurationFileName, false, localName);
					rulesLock.lock();
					sendRules = ci.getSendRules();
					receiveRules = ci.getReceiveRules();
					rulesLock.unlock();
				}
				if (!senderThread.isAlive()) {
					System.err
							.println("MessagePasser health check: sender thread died");
				}
				if (!receiverThread.isAlive()) {
					System.err
							.println("MessagePasser health check: receiver thread died");
				}
			}
		}
	}

	/**
	 * This thread keeps taking messages from the send buffer and send them.
	 * 
	 * @author Hao Gao
	 * @author Yinsu Chu
	 * 
	 */
	private class Sender implements Runnable {
		private LinkedBlockingQueue<Message> delayBuffer;

		public Sender() {
			this.delayBuffer = new LinkedBlockingQueue<Message>();
		}

		public void run() {
			if (!contactMap.containsKey(localName)) {
				return;
			}
			while (true) {
				try {
					Message message = sendBuffer.take();
					Socket clientSocket = null;
					String dest = message.getDest();

					// cannot send if receiver does not exist
					if (!contactMap.containsKey(dest)) {
						System.err.println("process with name " + dest
								+ " dose not exist");
						continue;
					}

					// if the socket does not exist, create one before sending
					if (!socketMap.containsKey(dest)) {
						Contact contact = contactMap.get(dest);
						clientSocket = NetTool.createSocket(contact.getIP(),
								contact.getPort());
						if (clientSocket == null) {
							System.out
									.println("failed to send message to "
											+ contact.getIP() + ":"
											+ contact.getPort());
							continue;
						}
						socketMap.put(dest, clientSocket);
					} else {
						clientSocket = socketMap.get(dest);
					}

					message.setSource(localName);
					message.setSequenceNumber(seqNum++);
					message.setDupe(false);

					// match rules before sending
					String action = checkRules(message, sendRules);
					if (action == null) {
						if (!sendMessage(clientSocket, message)) {
							System.err.println("failed to send message - "
									+ message.toString());
							socketMap.remove(dest);
						}
						clearDelayBuffer(clientSocket);
					} else if (action.equals(ACTION_DROP)) {
						System.out.println("drop rule matched");
						continue;
					} else if (action.equals(ACTION_DELAY)) {
						System.out.println("delay rule matched");
						delayBuffer.put(message);
					} else if (action.equals(ACTION_DUPLICATE)) {
						System.out.println("duplicate rule matched");
						Message dup = new TimeStampedMessage(null, null, null);
						dup.setDest(dest);
						dup.setKind(message.getKind());
						dup.setData(message.getData());
						dup.setSource(message.getSource());
						dup.setSequenceNumber(message.getSequenceNumber());
						dup.setDupe(true);
						if (!sendMessage(clientSocket, message)) {
							System.out.println("failed to send message - "
									+ message.toString());
							socketMap.remove(dest);
						}
						if (!sendMessage(clientSocket, dup)) {
							System.out.println("failed to send message - "
									+ message.toString());
							socketMap.remove(dest);
						}
						clearDelayBuffer(clientSocket);
					}
				} catch (InterruptedException ex) {
				}
			}
		}

		/**
		 * Upon each sending, clear delay buffer.
		 * 
		 * @param clientSocket
		 *            The socket to the remote side.
		 */
		private void clearDelayBuffer(Socket clientSocket) {
			while (!delayBuffer.isEmpty()) {
				try {
					Message message = delayBuffer.take();
					if (!sendMessage(clientSocket, message)) {
						System.out.println("failed to send message - "
								+ message.toString());
						socketMap.remove(message.getDest());
					}
				} catch (InterruptedException ex) {
				}
			}
		}

		/**
		 * Send a message from the given socket.
		 * 
		 * @param socket
		 *            The socket to send the message.
		 * @param message
		 *            The message to send.
		 * @return True on success, false otherwise.
		 */
		private boolean sendMessage(Socket socket, Message message) {
			OutputStream output = null;
			ObjectOutputStream objectOutput = null;
			try {
				output = socket.getOutputStream();
			} catch (Exception ex) {
				System.err.println("failed to get output stream - "
						+ ex.getMessage());
				return false;
			}
			try {
				objectOutput = new ObjectOutputStream(output);
			} catch (Exception ex) {
				System.err.println("failed to create object output stream - "
						+ ex.getMessage());
				if (objectOutput != null) {
					try {
						objectOutput.close();
					} catch (Exception nestedEx) {
						System.err
								.println("failed to close object output stream - "
										+ nestedEx);
					}
				}
				return false;
			}
			try {
				objectOutput.writeObject(message);
			} catch (Exception ex) {
				System.err.println("failed to send message - "
						+ ex.getMessage());
				if (objectOutput != null) {
					try {
						objectOutput.close();
					} catch (Exception nestedEx) {
						System.err
								.println("failed to close object output stream - "
										+ nestedEx);
					}
				}
				return false;
			}
			return true;
		}
	}

	/**
	 * This thread listens on the local server socket and spanws a worker thread
	 * if a remote node tries to send messages.
	 * 
	 * @author Hao Gao
	 * @author Yinsu Chu
	 * 
	 */
	private class Receiver implements Runnable {
		private ReentrantLock delayBufferLock;
		private LinkedBlockingQueue<Message> delayBuffer;

		public Receiver() {
			this.delayBufferLock = new ReentrantLock();
			this.delayBuffer = new LinkedBlockingQueue<Message>();
		}

		/**
		 * This thread is created if a remote host tries to send messages to the
		 * local node. Once created, it will continue to run to receive any
		 * future messages.
		 * 
		 * @author Hao Gao
		 * @author Yinsu Chu
		 * 
		 */
		private class ReceiverWorker implements Runnable {
			private Socket clientSocket;

			public ReceiverWorker(Socket clientSocket) {
				this.clientSocket = clientSocket;
			}

			public void run() {
				while (true) {
					Message message = receiveMessage(clientSocket);

					/*
					 * if failed to receive messages from the socket, it is
					 * probably the case the the socket has failed
					 */
					if (message == null) {
						System.err
								.println("failed to receive message from socket");
						NetTool.destroySocket(clientSocket);
						return;
					} else {
						System.out.println("message received - "
								+ message.toString());
					}

					String action = checkRules(message, receiveRules);
					try {
						if (action == null) {
							receiveBuffer.put(message);
							clearDelayBuffer();
						} else if (action.equals(ACTION_DROP)) {
							System.out.println("drop rule matched");
							continue;
						} else if (action.equals(ACTION_DELAY)) {
							System.out.println("delay rule matched");
							delayBufferLock.lock();
							delayBuffer.put(message);
							delayBufferLock.unlock();
						} else if (action.equals(ACTION_DUPLICATE)) {
							System.out.println("duplicate rule matched");
							Message dup = new TimeStampedMessage(null, null,
									null);
							dup.setDest(new String(message.getDest()));
							dup.setKind(new String(message.getKind()));
							dup.setData(message.getData());
							dup.setSource(new String(message.getSource()));
							dup.setSequenceNumber(message.getSequenceNumber());
							dup.setDupe(message.isDupe());
							receiveBuffer.put(message);
							receiveBuffer.put(dup);
							clearDelayBuffer();
						}
					} catch (InterruptedException ex) {
					}
				}
			}

			/*
			 * Upon each receiving, clear delay buffer.
			 */
			private void clearDelayBuffer() {
				try {
					delayBufferLock.lock();
					while (!delayBuffer.isEmpty()) {
						receiveBuffer.put(delayBuffer.take());
					}
					delayBufferLock.unlock();
				} catch (InterruptedException ex) {
				}
			}

			/**
			 * Receive a message from the given socket.
			 * 
			 * @param socket
			 *            The socket to receive the message.
			 * @return The received message, null on failure.
			 */
			private Message receiveMessage(Socket socket) {
				InputStream input = null;
				ObjectInputStream objectInput = null;
				try {
					input = socket.getInputStream();
				} catch (Exception ex) {
					System.err.println("failed to get input stream - "
							+ ex.getMessage());
					return null;
				}
				try {
					objectInput = new ObjectInputStream(input);
				} catch (Exception ex) {
					System.err.println("failed to get object input stream - "
							+ ex.getMessage());
					if (objectInput != null) {
						try {
							objectInput.close();
						} catch (Exception nestedEx) {
							System.err
									.println("failed to close object input stream - "
											+ nestedEx);
						}
					}
					return null;
				}
				Message incomingMessage = null;
				try {
					incomingMessage = (Message) objectInput.readObject();
				} catch (Exception ex) {
					System.err.println("failed to get incoming message - "
							+ ex.getMessage());
					if (objectInput != null) {
						try {
							objectInput.close();
						} catch (Exception nestedEx) {
							System.err
									.println("failed to close object input stream - "
											+ nestedEx);
						}
					}
					return null;
				}
				return incomingMessage;
			}
		}

		public void run() {
			Contact self = contactMap.get(localName);
			serverSocket = NetTool.createServerSocket(self.getIP(),
					self.getPort());

			// failure on creating server socket is a fatal error
			if (serverSocket == null) {
				System.err.println("cannot create server socket on "
						+ self.getIP() + ":" + self.getPort());
				return;
			}

			while (true) {
				Socket clientSocket = null;
				try {
					clientSocket = serverSocket.accept();
				} catch (IOException ex) {
					System.err.println("failed to accept incoming request - "
							+ ex.getMessage());
					continue;
				}

				// spawn a worker thread
				ReceiverWorker rw = new ReceiverWorker(clientSocket);
				Thread rwThread = new Thread(rw);
				rwThread.start();
			}
		}
	}

	public MessagePasser(String localConfigurationFileName, String localName,
			ConfigInfo configInfo) {
		this.localName = localName;
		this.localConfigurationFileName = localConfigurationFileName;
		this.sendBuffer = new LinkedBlockingQueue<Message>();
		this.receiveBuffer = new LinkedBlockingQueue<Message>();
		this.contactMap = configInfo.getContactMap();
		this.socketMap = new HashMap<String, Socket>();
		this.seqNum = 1;
		this.clockService = ClockService.getInstance();
		this.rulesLock = new ReentrantLock();
		this.sendRules = configInfo.getSendRules();
		this.receiveRules = configInfo.getReceiveRules();
		this.watcher = new Watcher();
		this.sender = new Sender();
		this.receiver = new Receiver();
		this.watcherThread = new Thread(watcher);
		this.senderThread = new Thread(sender);
		this.receiverThread = new Thread(receiver);
		this.watcherThread.start();
		this.senderThread.start();
		this.receiverThread.start();
	}

	/**
	 * Match a message against rules.
	 * 
	 * @param message
	 *            The message to check.
	 * @param rules
	 *            The rules (send or receive) to match.
	 * @return ACTION_DROP, ACTION_DELAY or ACTION_DUPLICATE, null on no match.
	 */
	private String checkRules(Message message,
			ArrayList<HashMap<String, Object>> rules) {
		rulesLock.lock();
		for (HashMap<String, Object> rule : rules) {
			boolean match = true;
			if (match && rule.containsKey(RULE_SRC)
					&& (!rule.get(RULE_SRC).equals(message.getSource()))) {
				match = false;
			}
			if (match && rule.containsKey(RULE_DST)
					&& (!rule.get(RULE_DST).equals(message.getDest()))) {
				match = false;
			}
			if (match && rule.containsKey(RULE_KIND)
					&& (!rule.get(RULE_KIND).equals(message.getKind()))) {
				match = false;
			}
			if (match
					&& rule.containsKey(RULE_SEQ_NUM)
					&& ((Integer) rule.get(RULE_SEQ_NUM)) != message
							.getSequenceNumber()) {
				match = false;
			}
			if (match && rule.containsKey(RULE_DUP)
					&& ((Boolean) rule.get(RULE_DUP) != message.isDupe())) {
				match = false;
			}
			if (match) {
				rulesLock.unlock();
				return (String) rule.get(RULE_ACTION);
			}
		}
		rulesLock.unlock();
		return null;
	}

	/**
	 * Put a message into the send buffer.
	 * 
	 * @param message
	 *            The message to send.
	 * @return The updated local time stamp due to this sending event.
	 */
	public TimeStamp send(Message message) {
		TimeStamp ts = null;
		try {
			if (clockService != null && message instanceof TimeStampedMessage) {
				ts = clockService.updateLocalTime();
				((TimeStampedMessage) message).setTimeStamp(ts);
			}
			sendBuffer.put(message);
		} catch (InterruptedException ex) {
		}
		return ts;
	}

	/**
	 * Take the next message from the receive buffer.
	 * 
	 * @return The next message in the receive buffer.
	 */
	public Message receive() {
		Message message = null;
		try {
			message = receiveBuffer.take();
			if (clockService != null && message instanceof TimeStampedMessage) {
				TimeStamp ts = clockService
						.updateLocalTime(((TimeStampedMessage) message)
								.getTimeStamp());
				((TimeStampedMessage) message).setTimeStamp(ts);
			}
		} catch (InterruptedException ex) {
		}
		return message;
	}
}
