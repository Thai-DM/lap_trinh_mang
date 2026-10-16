package com.smashprofs.common.model;

/** Per-match reliable event sequence; null killer means no attribution evidence. */
public record MatchEvent(long sequence,long tick,String type,String killer,String victim,String weapon,String cause) { }
