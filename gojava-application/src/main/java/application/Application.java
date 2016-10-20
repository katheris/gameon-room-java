/*******************************************************************************
 * Copyright (c) 2016 IBM Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package application;

import java.io.Closeable;
import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import javax.json.Json;
import javax.json.JsonObject;
import javax.json.JsonObjectBuilder;
import javax.json.JsonString;
import javax.json.JsonValue;
import javax.json.JsonValue.ValueType;
import javax.websocket.CloseReason;
import javax.websocket.CloseReason.CloseCodes;
import javax.websocket.EndpointConfig;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.RemoteEndpoint.Basic;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

import map.client.MapClient;
import map.client.model.RoomInfo;
import map.client.model.Site;

/**
 * A very simple room.
 *
 * All configuration for the room is contained / derived from the Config class.
 * This application has the following library dependencies (which are managed by jitpack.io).
 *
 * - com.github.gameontext:regutil
 *
 */
@ServerEndpoint("/room")
@ApplicationScoped
public class Application {
    
    @Inject
    private MapClient mapClient;

    private final static String USERNAME = "username";
    private final static String USERID = "userId";
    private final static String BOOKMARK = "bookmark";
    private final static String CONTENT = "content";
    private final static String LOCATION = "location";
    private final static String TYPE = "type";
    private final static String NAME = "name";
    private final static String EXIT = "exit";
    private final static String EXIT_ID = "exitId";
    private final static String FULLNAME = "fullName";
    private final static String DESCRIPTION = "description";
    
    private final String STEP1 = "Pick up knife in right hand.";
    private final String STEP2 = "Put knife in jam.";
    private final String STEP3 = "Scoop jam up with knife.";
    private final String STEP4 = "Pick up bread in left hand.";
    private final String STEP5 = "Put knife with jam on bread.";
    private final String STEP6 = "Move knife side to side.";
    
    private final String STEP1CODE = "3";
    private final String STEP2CODE = "2";
    private final String STEP3CODE = "4";
    private final String STEP4CODE = "1";
    private final String STEP5CODE = "6";
    private final String STEP6CODE = "5";

    private Set<String> playersInRoom = Collections.synchronizedSet(new HashSet<String>());

    private static long bookmark = 0;

    private final Set<Session> sessions = new CopyOnWriteArraySet<Session>();

    private RoomInfo roomInfo;
    // Set this so that the room can find out where it is
    private String siteId = "7b0d5c1eaeca86c5aa48458f0e14ce3f";
    
    @PostConstruct
    public void init() {
        if (siteId != null && !siteId.trim().isEmpty()) {
            Site site = mapClient.getSite(siteId);
            if (site != null) {
                roomInfo = site.getInfo();
            }
        } else {
            System.out.println("The room has no Site Id. Please set the Site Id so that the room can get information about itself");
        }

        // If the room doesn't know these things, use defaults
        if (roomInfo == null) {
            roomInfo = new RoomInfo();
        }
        
        if (roomInfo.getName() == null) {
            roomInfo.setName("Placeholder for jam sandwich.");
        }
        
        if (roomInfo.getFullName() == null) {
            roomInfo.setFullName("A room with no full name, should be jam sandwich");
        }
        
        if (roomInfo.getDescription() == null) {
            roomInfo.setDescription("You are in a room with a knife, some jam and some bread. There is a robot in the corner and a stack of papers on a desk.");
        }
    }
    
    
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Websocket methods..
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    @OnOpen
    public void onOpen(Session session, EndpointConfig ec) {
        System.out.println("A new connection has been made to the room.");

        //send ack
        sendRemoteTextMessage(session, "ack,{\"version\":[1]}");
    }

    @OnClose
    public void onClose(Session session, CloseReason r) {
        System.out.println("A connection to the room has been closed");
    }

    @OnError
    public void onError(Session session, Throwable t) {
        if(session!=null){
            sessions.remove(session);
        }
        System.out.println("Websocket connection has broken");
        t.printStackTrace();
    }

    @OnMessage
    public void receiveMessage(String message, Session session) throws IOException {
        String[] contents = splitRouting(message);

        // Who doesn't love switch on strings in Java 8?
        switch(contents[0]) {
        case "roomHello":
            sessions.add(session);
            addNewPlayer(session, contents[2]);
            break;
        case "room":
            processCommand(session, contents[2]);
            break;
        case "roomGoodbye":
            removePlayer(session, contents[2]);
            break;
        }
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Room methods..
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    
    private void sendLocation(Session session, String userid) {
        // now send the room info
        // this is the required response to a roomHello event, which is the
        // only reason we are in this method.
        JsonObjectBuilder response = Json.createObjectBuilder();
        response.add(TYPE, LOCATION);
        // now send the room info
        // this is the required response to a roomHello event, which is the
        // only reason we are in this method.
        response.add(NAME, roomInfo.getName());
        response.add(FULLNAME, roomInfo.getFullName());
        response.add(DESCRIPTION, roomInfo.getDescription());
        sendRemoteTextMessage(session, "player," + userid + "," + response.build().toString());
    }
    
    // add a new player to the room
    private void addNewPlayer(Session session, String json) throws IOException {
        if (session.getUserProperties().get(USERNAME) != null) {
            return; // already seen this user before on this socket
        }
        JsonObject msg = Json.createReader(new StringReader(json)).readObject();
        String username = getValue(msg.get(USERNAME));
        String userid = getValue(msg.get(USERID));

        if (playersInRoom.add(userid)) {
            // broadcast that the user has entered the room
            sendMessageToRoom(session, "Player " + username + " has entered the room", "You have entered the room",
                              userid);
            sendLocation(session, userid);
        }
        
    }

    // remove a player from the room.
    private void removePlayer(Session session, String json) throws IOException {
        sessions.remove(session);
        JsonObject msg = Json.createReader(new StringReader(json)).readObject();
        String username = getValue(msg.get(USERNAME));
        String userid = getValue(msg.get(USERID));
        playersInRoom.remove(userid);

        // broadcast that the user has left the room
        sendMessageToRoom(session, "Player " + username + " has left the room", null, userid);
    }

    // process a command
    private void processCommand(Session session, String json) throws IOException {
        JsonObject msg = Json.createReader(new StringReader(json)).readObject();
        String userid = getValue(msg.get(USERID));
        String username = getValue(msg.get(USERNAME));
        String content = getValue(msg.get(CONTENT)).toString();
        String lowerContent = content.toLowerCase();

        System.out.println("Command received from the user, " + content);

        // handle look command
        if (lowerContent.startsWith("/look")) {
            String object = null;
            if (lowerContent.length() > 5) {
                object = lowerContent.substring(6);
            }
            if (object == null) {
	            // resend the room description when we receive /look
	            JsonObjectBuilder response = Json.createObjectBuilder();
	            response.add(TYPE, LOCATION);
	            response.add(NAME, roomInfo.getName());
	            response.add(DESCRIPTION, roomInfo.getDescription());
	            sendRemoteTextMessage(session, "player," + userid + "," + response.build().toString());
				sendMessageToRoom(session, null, "There are the following objects: \n\n* Bread \n* Knife \n* Jam \n* Robot \n* Papers" 
						+ "\n\nThere is also a note tacked to the wall that says:\n\n/look and /use are your friends!", userid);
	            return;
            } else {
            	switch (object) {
	        	case "papers":
	        		sendMessageToRoom(session, null, "A stack of papers with numbered instructions: \n\n "
	        				+ STEP4CODE + ". " + STEP4 + "\n " + STEP2CODE + ". "
	        	            + STEP2 + "\n " + STEP1CODE + ". " + STEP1 + "\n " 
	        	            + STEP3CODE + ". " + STEP3 + "\n " + STEP6CODE + ". " + STEP6 + "\n "
	        	            		+ STEP5CODE + ". " + STEP5, userid);
            	    return;
	        	case "robot":
                    sendMessageToRoom(session, null, "A robot with a number console, a button and a screen displaying: \n\nEnter a 6-digit number.", userid);
                    return;
	        	case "bread":
	        		sendMessageToRoom(session, null, "A loaf of sliced bread, not very interesting by itself.", userid);
	        	    return;
	        	case "knife":
	        		sendMessageToRoom(session, null, "A blunt knife, you don't feel like examining it further.", userid);
	        	    return;
	        	case "jam":
	        		sendMessageToRoom(session, null, "A jar of jam, not very interesting by itself.", userid);
	        	    return;
	        	default:
            		sendMessageToRoom(session, null, "There is not object called " + object
            				+ "in this room, try looking at something else", userid);
                    return;
            	}
            }
        }
        
        if (lowerContent.startsWith("/use")) {
            String object = null;
            if (lowerContent.length() > 5) {
                object = lowerContent.substring(5);
            }
            if (object == null) {
                sendMessageToRoom(session, null, "You look around for something to use and lose your train of thought...", userid);
            } else if (object.equals("robot")){
                sendMessageToRoom(session, null, "You press a button on the robot and the screen displays:"
                        + "\n Command order required, please try again.", userid);
            } else if (object.startsWith("robot")) {
                String code = object.substring(6);
                if (code.equals(STEP1CODE + STEP2CODE + STEP3CODE + STEP4CODE + STEP5CODE + STEP6CODE)) {
                    sendMessageToRoom(session, null, "You punch some numbers into the console on the Robot and press a button, "
                            + "the robot makes a jam sandwich!", userid);
                } else {
                    sendMessageToRoom(session, null, "You punch some numbers into the console on the Robot and press a button, "
                            + "the console displays: \n\n ERROR: Incorrect code.", userid);
                }
            } else {
                sendMessageToRoom(session, null, "You walk over to the object to use it, but lose your train of thought...", userid);
            }
            return;
        }

        if (lowerContent.startsWith("/go")) {

            String exitDirection = null;
            if (lowerContent.length() > 4) {
                exitDirection = lowerContent.substring(4).toLowerCase();
            }

            
            if ( exitDirection == null) {
                sendMessageToRoom(session, null, "Hmm. Looks like you need to specify a direction. Try again?", userid);
            } else if (roomInfo.getDoors().getDoor(exitDirection) == null) {
                sendMessageToRoom(session, null, "Hmm. There is no "+ exitDirection + " door. Try again?", userid);
            } else {
                // Trying to go somewhere, eh?
                JsonObjectBuilder response = Json.createObjectBuilder();
                response.add(TYPE, EXIT)
                    .add(EXIT_ID, exitDirection)
                    .add(BOOKMARK, bookmark++)
                    .add(CONTENT, "Run Away!");

                sendRemoteTextMessage(session, "playerLocation," + userid + "," + response.build().toString());
            }
            return;
        }

        // reject all unknown commands
        if (lowerContent.startsWith("/")) {
            sendMessageToRoom(session, null, "Unrecognised command - sorry :-(", userid);
            return;
        }

        // everything else is just chat.
        sendChatMessage(session, content, userid, username);
        return;
    }
    
    private void jamSandwich() {
        /*Pick up bread in right hand
    	Pick up knife in left hand 
    	Put knife in jam
    	Scoop jam up with knife
    	Put knife with jam on bread
    	Move knife side to side*/
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Reply methods..
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    private void sendMessageToRoom(Session session, String messageForRoom, String messageForUser, String userid)
            throws IOException {
        JsonObjectBuilder response = Json.createObjectBuilder();
        response.add(TYPE, "event");

        JsonObjectBuilder content = Json.createObjectBuilder();
        if (messageForRoom != null) {
            content.add("*", messageForRoom);
        }
        if (messageForUser != null) {
            content.add(userid, messageForUser);
        }

        response.add(CONTENT, content.build());
        response.add(BOOKMARK, bookmark++);

        if(messageForRoom==null){
            sendRemoteTextMessage(session, "player," + userid + "," + response.build().toString());
        }else{
            broadcast(sessions, "player,*," + response.build().toString());
        }
    }

    private void sendChatMessage(Session session, String message, String userid, String username) throws IOException {
        JsonObjectBuilder response = Json.createObjectBuilder();
        response.add(TYPE, "chat");
        response.add(USERNAME, username);
        response.add(CONTENT, message);
        response.add(BOOKMARK, bookmark++);
        broadcast(sessions, "player,*," + response.build().toString());
    }

    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////
    // Util fns.
    ///////////////////////////////////////////////////////////////////////////////////////////////////////////////

    private String[] splitRouting(String message) {
        ArrayList<String> list = new ArrayList<>();

        int brace = message.indexOf('{');
        int i = 0;
        int j = message.indexOf(',');
        while (j > 0 && j < brace) {
            list.add(message.substring(i, j));
            i = j + 1;
            j = message.indexOf(',', i);
        }
        list.add(message.substring(i));

        return list.toArray(new String[] {});
    }

    private static String getValue(JsonValue value) {
        if (value.getValueType().equals(ValueType.STRING)) {
            JsonString s = (JsonString) value;
            return s.getString();
        } else {
            return value.toString();
        }
    }

    /**
     * Simple text based broadcast.
     *
     * @param session
     *            Target session (used to find all related sessions)
     * @param message
     *            Message to send
     * @see #sendRemoteTextMessage(Session, RoutedMessage)
     */
    public void broadcast(Set<Session> sessions, String message) {
        for (Session s : sessions) {
            sendRemoteTextMessage(s, message);
        }
    }

    /**
     * Try sending the {@link RoutedMessage} using
     * {@link Session#getBasicRemote()}, {@link Basic#sendObject(Object)}.
     *
     * @param session
     *            Session to send the message on
     * @param message
     *            Message to send
     * @return true if send was successful, or false if it failed
     */
    public boolean sendRemoteTextMessage(Session session, String message) {
        if (session.isOpen()) {
            try {
                session.getBasicRemote().sendText(message);
                return true;
            } catch (IOException ioe) {
                // An IOException, on the other hand, suggests the connection is
                // in a bad state.
                System.out.println("Unexpected condition writing message: " + ioe);
                tryToClose(session, new CloseReason(CloseCodes.UNEXPECTED_CONDITION, trimReason(ioe.toString())));
            }
        }
        return false;
    }

    /**
     * {@code CloseReason} can include a value, but the length of the text is
     * limited.
     *
     * @param message
     *            String to trim
     * @return a string no longer than 123 characters.
     */
    private static String trimReason(String message) {
        return message.length() > 123 ? message.substring(0, 123) : message;
    }

    /**
     * Try to close the WebSocket session and give a reason for doing so.
     *
     * @param s
     *            Session to close
     * @param reason
     *            {@link CloseReason} the WebSocket is closing.
     */
    public void tryToClose(Session s, CloseReason reason) {
        try {
            s.close(reason);
        } catch (IOException e) {
            tryToClose(s);
        }
    }

    /**
     * Try to close a {@code Closeable} (usually once an error has already
     * occurred).
     *
     * @param c
     *            Closable to close
     */
    public void tryToClose(Closeable c) {
        if (c != null) {
            try {
                c.close();
            } catch (IOException e1) {
            }
        }
    }

}
