/**
 *
 * <p>Title: BattleBoardGame.java</p>
 * 
 * <p>Description: </p>
 *
 * @author Jeff Kesselman
 * @version 1.0
 */
/*****************************************************************************
     * Copyright (c) 2006 Sun Microsystems, Inc.  All Rights Reserved.
     * Redistribution and use in source and binary forms, with or without
     * modification, are permitted provided that the following conditions are met:
     *
     * - Redistribution of source code must retain the above copyright notice,
     *   this list of conditions and the following disclaimer.
     *
     * - Redistribution in binary form must reproduce the above copyright notice,
     *   this list of conditions and the following disclaimer in the documentation
     *   and/or other materails provided with the distribution.
     *
     * Neither the name Sun Microsystems, Inc. or the names of the contributors
     * may be used to endorse or promote products derived from this software
     * without specific prior written permission.
     *
     * This software is provided "AS IS," without a warranty of any kind.
     * ALL EXPRESS OR IMPLIED CONDITIONS, REPRESENTATIONS AND WARRANTIES, INCLUDING
     * ANY IMPLIED WARRANT OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE OR
     * NON-INFRINGEMEN, ARE HEREBY EXCLUDED.  SUN MICROSYSTEMS, INC. ("SUN") AND
     * ITS LICENSORS SHALL NOT BE LIABLE FOR ANY DAMAGES SUFFERED BY LICENSEE AS
     * A RESULT OF USING, MODIFYING OR DESTRIBUTING THIS SOFTWARE OR ITS
     * DERIVATIVES.  IN NO EVENT WILL SUN OR ITS LICENSORS BE LIABLE FOR ANY LOST
     * REVENUE, PROFIT OR DATA, OR FOR DIRECT, INDIRECT, SPECIAL, CONSEQUENTIAL,
     * INCIDENTAL OR PUNITIVE DAMAGES.  HOWEVER CAUSED AND REGARDLESS OF THE THEORY
     * OF LIABILITY, ARISING OUT OF THE USE OF OUR INABILITY TO USE THIS SOFTWARE,
     * EVEN IF SUN HAS BEEN ADVISED OF THE POSSIBILITY OF SUCH DAMAGES.
     *
     * You acknowledge that this software is not designed or intended for us in
     * the design, construction, operation or maintenance of any nuclear facility
     *
     *****************************************************************************/

package com.sun.gi.apps.jeffboard;

import java.io.Serializable;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;

import com.sun.gi.comm.routing.ChannelID;
import com.sun.gi.comm.routing.UserID;
import com.sun.gi.logic.GLO;
import com.sun.gi.logic.GLOReference;
import com.sun.gi.logic.SimTask;

/**
 *
 * <p>Title: BattleBoardGame.java</p>
 * <p>Description: </p>
 * <p>Copyright: Copyright (c) 2004 Sun Microsystems, Inc.</p>
 * <p>Company: Sun Microsystems, Inc</p>
 * @author Jeff Kesselman
 * @version 1.0
 */
public class BattleBoardGame implements GLO{
	private static final int CITY_COUNT = 2;
	private static final int BOARD_WIDTH = 10;
	private static final int BOARD_HEIGHT = 8;
	static final int MAX_PLAYERS = 3; // 3 players per game
	
	
	/**
	 * 
	 */
	private static final long serialVersionUID = 1L;
	
	LinkedList<UserID> playingList = new LinkedList<UserID>();
	List<UserID> withdrawnList = new LinkedList<UserID>();
	Map<UserID,String> screenNames = 
		new HashMap<UserID,String>();
	Map<String,UserID> reverseScreenNames = 
		new HashMap<String,UserID>();	
	Map<UserID,GLOReference> idToGLORef = 
		new HashMap<UserID,GLOReference>();
	ChannelID controlChannel;
	Map<UserID,BattleMap> playerMaps = new HashMap<UserID,BattleMap>();
	private String gameName;
	private ChannelID gameChannel;
	private String turnOrderString;
	private int joinerCount=0;
	private int currentPlayer = 0;
	
	public BattleBoardGame(SimTask task, ChannelID controlChannel,
			String gameName){
		this.controlChannel = controlChannel;
		this.gameName = gameName;
		gameChannel = task.openChannel("bb_"+gameName);
		// lock it for security
		task.lock(gameChannel,true);
	}
	
	/**
	 * @param playerRef
	 */
	public void addPlayer(GLOReference playerRef, UserID uid) {
		if (isFull()) {
			System.err.print(
					"BATTLEBOARD ERROR: Tried to add too many players");
			return;
		}
		playingList.add(uid);
		idToGLORef.put(uid,playerRef);
	}

	/**
	 * @return
	 */
	public boolean isFull() {
		return (playingList.size()==MAX_PLAYERS);	
	}

	
	public void setScreenName(UserID uid, String screenName){
		SimTask task = SimTask.getCurrent();
		if (reverseScreenNames.get(screenName)!=null){
			sendData(task,controlChannel,uid,"already-joined");		
			return;
		}		
		screenNames.put(uid,screenName);	
		reverseScreenNames.put(screenName,uid);
		setupBoard(task,uid);	
		task.join(uid,gameChannel);
	}

	/**
	 * @param task 
	 * @param controlChannel2
	 * @param uid
	 * @param string
	 */
	private void sendData(SimTask task, ChannelID cid, UserID uid, String string) {
		ByteBuffer buff = ByteBuffer.allocate(string.length());
		buff.put(string.getBytes());
		task.sendData(cid,new UserID[]{uid},buff,true);
		
	}

	/**
	 * 
	 */
	private void sendTurnOrder(SimTask task) {
		StringBuffer out = new StringBuffer("turn-order");
		for(UserID uid : playingList){
			out.append(" "+screenNames.get(uid));
		}		
		String turnOrderString = out.toString();		// open a game channel
		
		for(UserID uid : screenNames.keySet()){
			sendData(task,gameChannel,uid,turnOrderString);
		}
	}
	
	public void joinedChannel(UserID uid, ChannelID cid){
		SimTask task = SimTask.getCurrent();
		if (cid.equals(gameChannel)){
			if (screenNames.containsKey(uid)){
				joinerCount++;
				if (joinerCount==MAX_PLAYERS){ //all here
					sendTurnOrder(task);
					currentPlayer = -1;
					nextMove(task);
				}
			} else { // alien, boot em
				task.leave(uid,cid);
			}
		}
	}

	/**
	 * 
	 */
	private void nextMove(SimTask task) {	
		currentPlayer++;
		currentPlayer %= playingList.size();
		if (playingList.size()>1){
			withdrawnList.clear(); // temporary list to handle edge case
			UserID thisPlayer = playingList.get(currentPlayer);
			sendData(task,gameChannel,thisPlayer,"your-move");
			String outstr = "move-started "+screenNames.get(thisPlayer);
			for(UserID id : screenNames.keySet()){
				if (id!=thisPlayer){
					sendData(task,gameChannel,id,outstr);
				}
			}
			
		} else {
			gameOver();
		}				
	}
	
	public void passMove(UserID uid){
		if (!uid.equals(playingList.get(currentPlayer))){
			System.err.println("BB ERROR: Non current player tried to pass");
			return;
		}
		SimTask task = SimTask.getCurrent();
		String outstr = "move-ended "+screenNames.get(uid)+" pass";
		for(UserID id : screenNames.keySet()){			
				sendData(task,gameChannel,id,outstr);
			
		}
		nextMove(task);
	}
	
	public void makeMove(UserID from, String bombedPlayer,int x, int y){
		SimTask task = SimTask.getCurrent();
		UserID thisPlayer = playingList.get(currentPlayer);
		if (!thisPlayer.equals(from)){
			System.err.println("BB ERROR: Player "+from+
					" moved out of turn.");
			System.err.println("BB ERROR: Expected player "+thisPlayer);
			return;
		}
		UserID target = reverseScreenNames.get(bombedPlayer);
		if (withdrawnList.contains(target)){
			sendData(task,gameChannel,thisPlayer,"your-move");
			withdrawnList.clear();
			return;
			
		}
		withdrawnList.clear();
		if (target==null){
			System.err.println(
				"BB ERROR: Tried to bomb nonexistant player: "+bombedPlayer);
			passMove(thisPlayer);
			return;
		}
		if (!playingList.contains(target)){
			System.err.println(
					"BB ERROR: Tried to bomb dead player: "+bombedPlayer);
			passMove(thisPlayer);
			return;
		}
		BattleMap map = playerMaps.get(target);
		String result = map.bomb(x,y);
		if (!map.isAlive()){
			removePlayer(target);
		}
		String outstr = "move-ended "+screenNames.get(thisPlayer)+" bomb "+
			screenNames.get(target)+" "+x+" "+y+" "+result;          ;
		for(UserID id : screenNames.keySet()){			
				sendData(task,gameChannel,id,outstr);	
		}
		nextMove(task);
	}
	
	public void withdraw(UserID uid){
		withdrawnList.add(uid);
		BattleMap map = playerMaps.get(uid);
		map.withdraw();
		removePlayer(uid);
	}


	/**
	 * @param uid 
	 * 
	 */
	private void removePlayer(UserID uid) {
		int idx = playingList.indexOf(uid);
		playingList.remove(uid);
		// adjust the current player index for moved players
		if (currentPlayer>=idx){
			currentPlayer--;
		}
	}

	/**
	 * 
	 */
	private void gameOver() {
		SimTask task = SimTask.getCurrent();
		UserID uid = playingList.get(currentPlayer);
		for(Entry<UserID,GLOReference> entry : idToGLORef.entrySet()){
			GLOReference ref = entry.getValue();
			BattleBoardPlayer player = 
				(BattleBoardPlayer)ref.get(task);
			if (uid.equals(entry.getKey())){
				player.gameOver(true);
			} else {
				player.gameOver(false);
			}
		}
		
		
	}

	/**
	 * @param uid
	 */
	private void setupBoard(SimTask task ,UserID uid) {
		BattleMap map = new BattleMap(CITY_COUNT,
				BOARD_WIDTH,BOARD_HEIGHT);
		playerMaps.put(uid,map);
		StringBuffer out = new StringBuffer("ok "+BOARD_WIDTH+" "
				+BOARD_HEIGHT+" "+CITY_COUNT);
		for(int[] city : map.getCityList()){
			out.append(" "+city[0]+" "+city[1]);
		}
		sendData(task,gameChannel,uid,out.toString());
		
	}

}

class BattleMap implements Serializable{
	List<int[]> cityList = new ArrayList<int[]>();
	private boolean withdrawn = false;
	
	public BattleMap(int cityCount, int width, int height){
		int count = 0;
		while (count<cityCount){
			int x = (int)(Math.random()*width);
			int y = (int)(Math.random()*height);
			boolean found = false;
			for(int[] city : cityList){
				if ((city[0]==x)&&(city[1]==y)){ // duplicate
					found = true;
					break;
				}
			}
			if (!found){
				cityList.add(new int[] {x,y});
				count++;
			}
		}
	}

	/**
	 * @return
	 */
	public List<int[]> getCityList() {
		return cityList;
	}

	/**
	 * @param x
	 * @param y
	 */
	public String bomb(int x, int y) {
		for(Iterator<int[]> iter = cityList.iterator();iter.hasNext();){
			int[] pos = iter.next();
			if ((pos[0]==x)&&(pos[1]==y)){ // hit
				iter.remove();
				if (cityList.isEmpty()){
					return "LOSS";
				} else {
					return "HIT";
				}
			}
		}
		for(Iterator<int[]> iter = cityList.iterator();iter.hasNext();){
			int[] pos = iter.next();
			if ((Math.abs(pos[0]-x)<=1)&&
				(Math.abs(pos[1]-y)<=1)){  // near miss
					return "NEAR_MISS";
			}
		}
		return "MISS";
	}

	/**
	 * @return
	 */
	public boolean isAlive() {
		// TODO Auto-generated method stub
		return !cityList.isEmpty();
	}

	/**
	 * 
	 */
	public void withdraw() {
		withdrawn = true;			
	}
}
