package org.sp001.core;

/** Active network time excludes synchronous downstream consumption; a separate wall cap remains. */
final class NetworkWaitBudget {
    private final long began,limit;private long pausedAt,pausedMs;private boolean paused;
    NetworkWaitBudget(long now,long limit){if(limit<1||limit>600000)throw new IllegalArgumentException("NETWORK_BUDGET");began=now;this.limit=limit;}
    synchronized void pause(long now){if(paused)throw new IllegalStateException("BUDGET_ALREADY_PAUSED");paused=true;pausedAt=now;}
    synchronized void resume(long now){if(!paused)throw new IllegalStateException("BUDGET_NOT_PAUSED");pausedMs+=Math.max(0,now-pausedAt);paused=false;}
    synchronized boolean expired(long now){return now-began-pausedMs-(paused?Math.max(0,now-pausedAt):0)>=limit;}
}
