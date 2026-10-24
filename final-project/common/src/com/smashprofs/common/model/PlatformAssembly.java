package com.smashprofs.common.model;

import java.util.*;

/** Connected collision rectangles that describe one visual one-way surface. */
public final class PlatformAssembly {
    public record Surface(float x,float top,float width) { }
    private PlatformAssembly() { }
    public static Set<Integer> connected(List<Surface> surfaces,int start) {
        var result=new HashSet<Integer>();result.add(start);
        boolean added;
        do {
            added=false;
            for(int i=0;i<surfaces.size();i++) {
                if(result.contains(i)) { continue; }
                var a=surfaces.get(i);
                for(int j:List.copyOf(result)) {
                    var b=surfaces.get(j);
                    // Half a tile in height, touching/overlapping horizontally. Distinct lower floors stay solid.
                    if(Math.abs(a.top-b.top)<=.08f && a.x<=b.x+b.width+.005f && b.x<=a.x+a.width+.005f) {
                        result.add(i);added=true;break;
                    }
                }
            }
        } while(added);
        return Set.copyOf(result);
    }
}
