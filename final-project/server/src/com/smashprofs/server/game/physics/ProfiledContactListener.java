package com.smashprofs.server.game.physics;

import com.badlogic.gdx.physics.box2d.*;
import com.smashprofs.server.metrics.PerformanceMetrics;
import java.util.function.Supplier;

/** Inclusive callback time within world.step, so it must not be added to PHYSICS_STEP. */
final class ProfiledContactListener implements ContactListener {
    private final ContactListener delegate; private final Supplier<PerformanceMetrics> metrics;
    ProfiledContactListener(ContactListener delegate,Supplier<PerformanceMetrics> metrics) { this.delegate=delegate;this.metrics=metrics; }
    private void record(long start) { metrics.get().record(PerformanceMetrics.Stage.COLLISION_CALLBACKS,System.nanoTime()-start); }
    @Override public void beginContact(Contact c) { long s=System.nanoTime();try{delegate.beginContact(c);}finally{record(s);} }
    @Override public void endContact(Contact c) { long s=System.nanoTime();try{delegate.endContact(c);}finally{record(s);} }
    @Override public void preSolve(Contact c,Manifold m) { long s=System.nanoTime();try{delegate.preSolve(c,m);}finally{record(s);} }
    @Override public void postSolve(Contact c,ContactImpulse i) { long s=System.nanoTime();try{delegate.postSolve(c,i);}finally{record(s);} }
}
