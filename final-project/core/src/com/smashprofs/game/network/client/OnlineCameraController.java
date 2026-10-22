package com.smashprofs.game.network.client;

/** Player-following framing with a lower limit at the visible lava surface. */
public final class OnlineCameraController {
    public record Frame(float x,float y,float width,float height) { }
    private Frame current;
    public void reset() { current=null; }
    public Frame update(float minX,float maxX,float minY,float maxY,float aspect,float mapWidth,float mapHeight,float delta) {
        if(!Float.isFinite(aspect) || aspect<=0) { aspect=16f/9; }
        float width=Math.min(Math.max(5.5f,mapWidth+.6f),Math.max(5.5f,Math.max(maxX-minX+1.5f,(maxY-minY+1.8f)*aspect)));
        float height=width/aspect,halfX=Math.max(0,(mapWidth-width)/2);
        float halfY=Math.max(0,(mapHeight-height)/2+height*.09f);
        float x=clamp((minX+maxX)/2,mapWidth/2-halfX,mapWidth/2+halfX);
        float y=Math.max(height/2-.45f,clamp((minY+maxY)/2+.1f,mapHeight/2-halfY,
                mapHeight/2+halfY+Math.max(0,maxY-mapHeight)));
        if(current==null) { current=new Frame(x,y,width,height);return current; }
        float dt=Float.isFinite(delta)?Math.max(0,Math.min(delta,.1f)):0;
        float alpha=(float)-Math.expm1(-9*dt);
        float smoothWidth=current.width+(width-current.width)*alpha,smoothHeight=smoothWidth/aspect;
        current=new Frame(current.x+(x-current.x)*alpha,Math.max(smoothHeight/2-.45f,current.y+(y-current.y)*alpha),smoothWidth,smoothHeight);
        return current;
    }
    private static float clamp(float value,float low,float high) { return Math.max(low,Math.min(high,value)); }
}
