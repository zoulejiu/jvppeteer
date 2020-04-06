package com.ruiyun.jvppeteer.protocol.coverage;

import com.ruiyun.jvppeteer.transport.websocket.CDPSession;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

public class CSSCoverage {

    private  CDPSession client;

    private  boolean enabled;

    private  HashMap<Object, Object> stylesheetURLs;

    private  HashMap stylesheetSources;

    private  List<Object> eventListeners;

    private  boolean resetOnNavigation;

    public CSSCoverage(CDPSession client){
        this.client = client;
        this.enabled = false;
        this.stylesheetURLs = new HashMap<>();
        this.stylesheetSources = new HashMap();
        this.eventListeners = new ArrayList<>();
        this.resetOnNavigation = false;
    }
}
