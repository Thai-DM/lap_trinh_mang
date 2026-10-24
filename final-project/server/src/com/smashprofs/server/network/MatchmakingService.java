package com.smashprofs.server.network;

import com.smashprofs.common.protocol.ProtocolException;
import com.smashprofs.server.room.*;
import java.util.Set;
import java.util.UUID;

/** Server-owned casual pairing. Lock order: session -> matchmaker -> room; never another session. */
final class MatchmakingService {
    private static final Set<String> CHARACTERS=Set.of("Alex","Luca","Maurice","Leo","Viktor");
    private final RoomManager rooms;
    private final JoinRoomHandler joins;
    private final PlayerReadyHandler ready;
    MatchmakingService(RoomManager rooms,JoinRoomHandler joins,PlayerReadyHandler ready) {
        this.rooms=rooms;this.joins=joins;this.ready=ready;
    }
    synchronized GameRoom find(ClientSession session,boolean boss,String character,String requestId) throws ProtocolException {
        if(!session.supportsFighter() || boss && !session.supportsBoss()) { throw failure("GAMEPLAY_CAPABILITY_REQUIRED",requestId); }
        if(!CHARACTERS.contains(character)) { throw failure("INVALID_PAYLOAD",requestId); }
        if(session.getRoomId()!=null) {
            var current=rooms.getRoom(session.getRoomId());
            if(current!=null && current.isMatchmaking() && current.getRules().boss()==boss
                    && character.equals(current.getSelectedCharacter(session.getPlayerId()))) { return current; }
            throw failure("INVALID_ROOM_STATE",requestId);
        }
        for(var room:rooms.getRooms()) {
            synchronized(room) {
                if(room.isClosed() || rooms.getRoom(room.getRoomId())!=room || !room.isMatchmaking()
                        || room.getRules().boss()!=boss || room.getPlayerCount()==0 || room.getPlayerCount()>=2
                        || room.getPhase()!=RoomPhase.WAITING && room.getPhase()!=RoomPhase.READY) { continue; }
                return enter(session,room,character,requestId);
            }
        }
        final GameRoom room;
        try { room=rooms.createRoom("quick-"+UUID.randomUUID()); }
        catch(IllegalStateException full) { throw failure("MATCHMAKING_FULL",requestId); }
        synchronized(room) {
            room.configureMatchmaking(session.getPlayerId(),boss);
            try { return enter(session,room,character,requestId); }
            catch(ProtocolException failure) { if(room.getPlayerCount()==0) { rooms.removeRoom(room.getRoomId()); }throw failure; }
        }
    }
    private GameRoom enter(ClientSession session,GameRoom room,String character,String requestId) throws ProtocolException {
        if(!joins.handle(session,room.getRoomId()).isSuccess()) { throw failure("INVALID_ROOM_STATE",requestId); }
        if(!ready.handle(session,new PlayerReadyHandler.ReadyRequest(room.getRoomId(),character,false)).isSuccess()) {
            room.leavePlayer(session.getPlayerId());session.leaveRoom();
            throw failure("INVALID_SESSION",requestId);
        }
        return room;
    }
    private static ProtocolException failure(String code,String requestId) {
        return new ProtocolException(code,"Cannot find a match: "+code,requestId);
    }
}
