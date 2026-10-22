package com.smashprofs.server.cluster;

import com.smashprofs.common.model.*;

/** Deterministic valid input profiles; no client-authoritative position/damage/result fields. */
public final class LoadWorkload {
    public enum Profile { IDLE,MOVEMENT,COMBAT,CHURN,SLOW_CLIENT,IMBALANCED }
    private LoadWorkload() { }
    public static PlayerInput input(Profile profile,PlayerId player,long sequence,int botIndex) {
        if(profile==Profile.IMBALANCED) { profile=botIndex/4%3==0?Profile.COMBAT:Profile.IDLE; }
        boolean movement=profile==Profile.MOVEMENT || profile==Profile.COMBAT;
        boolean combat=profile==Profile.COMBAT;
        float axis=movement?(((sequence/40+botIndex)%2==0)?1:-1):0;
        return new PlayerInput(player,sequence,sequence*3,axis,movement&&sequence%40==3,
                combat&&sequence%30==10,combat&&sequence%6==0,combat&&sequence%20<5,combat&&sequence%40==0);
    }
}
