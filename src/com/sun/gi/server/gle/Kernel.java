package com.sun.gi.server.gle;

/**
 * <p>Title: </p>
 * <p>Description: </p>
 * <p>Copyright: Copyright (c) 2003</p>
 * <p>Company: </p>
 * @author not attributable
 * @version 1.0
 */

public interface Kernel {
    public void queueTask(Task task);
    public Class resolveClass(String fqcn);
    public Class resolveClass(String fqcn,float version);
}