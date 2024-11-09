/* 
 * Copyright 2010 Aalto University, ComNet
 * Released under GPLv3. See LICENSE.txt for details. 
 */
package routing;

import java.util.ArrayList;
import java.util.List;

import core.Connection;
import core.DTNHost;
import core.Message;
import core.Settings;
import java.util.HashMap;
import core.Connection;
import core.SimClock;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import routing.community.Duration;

/**
 * Implementation of Spray and wait router as depicted in
 * <I>Spray and Wait: An Efficient Routing Scheme for Intermittently Connected
 * Mobile Networks</I> by Thrasyvoulos Spyropoulus et al.
 *
 */
public class SprayAndWaitRouter1 extends ActiveRouter {

    /**
     * identifier for the initial number of copies setting ({@value})
     */
    public static final String NROF_COPIES = "nrofCopies";
    /**
     * identifier for the binary-mode setting ({@value})
     */
    public static final String BINARY_MODE = "binaryMode";
    /**
     * SprayAndWait router's settings name space ({@value})
     */
    public static final String SPRAYANDWAIT_NS = "SprayAndWaitRouter";
    /**
     * Message property key
     */
    public static final String MSG_COUNT_PROPERTY = SPRAYANDWAIT_NS + "."
            + "copies";

    protected int initialNrofCopies;
    protected boolean isBinary;

    //TUON
    // private MessageRouter router;
    protected Map<DTNHost, Double> startEncounterTimestamps;
    protected Map<DTNHost, Double> startIntercontactTimestamps;

    protected Map<DTNHost, List<Duration>> connHistory;
    protected Map<DTNHost, List<Duration>> connIntercontactistory;

    public SprayAndWaitRouter1(Settings s) {
        super(s);
        Settings snwSettings = new Settings(SPRAYANDWAIT_NS);

        initialNrofCopies = snwSettings.getInt(NROF_COPIES);
        isBinary = snwSettings.getBoolean(BINARY_MODE);

        //TUON
        startEncounterTimestamps = new HashMap<>();
        startIntercontactTimestamps = new HashMap<>();
        connHistory = new HashMap<>();
        connIntercontactistory = new HashMap<>();

    }

    /**
     * Copy constructor.
     *
     * @param r The router prototype where setting values are copied from
     */
    protected SprayAndWaitRouter1(SprayAndWaitRouter1 r) {
        super(r);
        this.initialNrofCopies = r.initialNrofCopies;
        this.isBinary = r.isBinary;

        //TUON
    }

    @Override
    public int receiveMessage(Message m, DTNHost from) {
        return super.receiveMessage(m, from);
    }

    @Override
    public Message messageTransferred(String id, DTNHost from) {
        Message msg = super.messageTransferred(id, from);
        Integer nrofCopies = (Integer) msg.getProperty(MSG_COUNT_PROPERTY);

        assert nrofCopies != null : "Not a SnW message: " + msg;

        if (isBinary) {
            /* in binary S'n'W the receiving node gets ceil(n/2) copies */
            nrofCopies = (int) Math.ceil(nrofCopies / 2.0);
        } else {
            /* in standard S'n'W the receiving node gets only single copy */
            nrofCopies = 1;
        }

        msg.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
        return msg;
    }

    @Override
    public boolean createNewMessage(Message msg) {
        makeRoomForNewMessage(msg.getSize());

        msg.setTtl(this.msgTtl);
        msg.addProperty(MSG_COUNT_PROPERTY, new Integer(initialNrofCopies));
        addToMessages(msg, true);
        return true;
    }

    @Override
    public void update() {
        super.update();
        if (!canStartTransfer() || isTransferring()) {
            return; // nothing to transfer or is currently transferring 
        }

        /* try messages that could be delivered to final recipient */
        if (exchangeDeliverableMessages() != null) {
            return;
        }

        /* create a list of SAWMessages that have copies left to distribute */
        @SuppressWarnings(value = "unchecked")
        List<Message> copiesLeft = sortByQueueMode(getMessagesWithCopiesLeft());

        if (copiesLeft.size() > 0) {
            /* try to send those messages */
            this.tryMessagesToConnections(copiesLeft, getConnections());
        }
    }

    /**
     * Creates and returns a list of messages this router is currently carrying
     * and still has copies left to distribute (nrof copies > 1).
     *
     * @return A list of messages that have copies left
     */
    protected List<Message> getMessagesWithCopiesLeft() {
        List<Message> list = new ArrayList<Message>();

        for (Message m : getMessageCollection()) {
            Integer nrofCopies = (Integer) m.getProperty(MSG_COUNT_PROPERTY);
            assert nrofCopies != null : "SnW message " + m + " didn't have "
                    + "nrof copies property!";
            if (nrofCopies > 1) {
                list.add(m);
            }
        }

        return list;
    }

    /**
     * Called just before a transfer is finalized (by
     * {@link ActiveRouter#update()}). Reduces the number of copies we have left
     * for a message. In binary Spray and Wait, sending host is left with
     * floor(n/2) copies, but in standard mode, nrof copies left is reduced by
     * one.
     */
    @Override
    protected void transferDone(Connection con) {
        Integer nrofCopies;
        String msgId = con.getMessage().getId();
        /* get this router's copy of the message */
        Message msg = getMessage(msgId);

        if (msg == null) { // message has been dropped from the buffer after..
            return; // ..start of transfer -> no need to reduce amount of copies
        }

        /* reduce the amount of copies left */
        nrofCopies = (Integer) msg.getProperty(MSG_COUNT_PROPERTY);
        if (isBinary) {
            nrofCopies /= 2;
        } else {
            nrofCopies--;
        }
        msg.updateProperty(MSG_COUNT_PROPERTY, nrofCopies);
    }

    @Override
    public SprayAndWaitRouter1 replicate() {
        return new SprayAndWaitRouter1(this);
    }

    private double calculateUspace(DTNHost host) {//perhitungan utilitas buffer (Uspace)
        double InitialSize = host.getRouter().getBufferSize();
        double BufferFree = host.getRouter().getFreeBufferSize();

        double Uspace = (InitialSize - BufferFree);

        return Uspace;

    }

    public void connectionUp(DTNHost thisHost, DTNHost peer) {
        double lastDisconnectTime = getLastDisconectedFor(peer);
        double currentTime = SimClock.getTime();

        this.startEncounterTimestamps.put(peer, currentTime);

        List<Duration> intercontactHistory;
        if (!connIntercontactistory.containsKey(peer)) {
            intercontactHistory = new LinkedList<Duration>();
        } else {
            intercontactHistory = connIntercontactistory.get(peer);
        }

        // add this connection to the list
        if (currentTime - lastDisconnectTime > 0) {
            intercontactHistory.add(new Duration(lastDisconnectTime, currentTime));
        }

        connIntercontactistory.put(peer, intercontactHistory);
        startIntercontactTimestamps.remove(peer);
    }

    public void connectionDown(DTNHost thisHost, DTNHost peer) {
        double lastEncounterTime = getLastEncounterFor(peer);
        double currentTime = SimClock.getTime();

        startIntercontactTimestamps.put(peer, currentTime);

        // Find or create the connection history list
        List<Duration> encounterHistory;
        if (!connHistory.containsKey(peer)) {
            encounterHistory = new LinkedList<Duration>();
        } else {
            encounterHistory = connHistory.get(peer);
        }

        // add this connection to the list
        if (currentTime - lastEncounterTime > 0) {
            encounterHistory.add(new Duration(lastEncounterTime, currentTime));
        }

        connHistory.put(peer, encounterHistory);

        startEncounterTimestamps.remove(peer);

    }

    private double getLastEncounterFor(DTNHost host) {
        if (startEncounterTimestamps.containsKey(host)) {
            return startEncounterTimestamps.get(host);
        } else {
            return 0;
        }
    }

    /**
     * Get the time when connection is down during this connection
     *
     * @param host Peer of this connection
     * @return time
     */
    private double getLastDisconectedFor(DTNHost host) {
        if (startIntercontactTimestamps.containsKey(host)) {
            return startIntercontactTimestamps.get(host);
        } else {
            return 0;
        }
    }

    private double calculateConnTime() {
        double connTime = 0;

        for (List<Duration> encounterHistory : connHistory.values()) {
            for (Duration durasi : encounterHistory) {
                connTime += durasi.end - durasi.start;
            }

        }
        return connTime;
    }

    private double calculateInterConn() {
        double interConTime = 0;

        for (List<Duration> intercontactHist : connIntercontactistory.values()) {
            for (Duration d : intercontactHist) {
                interConTime += d.end - d.start / intercontactHist.size();
            }
        }
        return interConTime;
    }

}
