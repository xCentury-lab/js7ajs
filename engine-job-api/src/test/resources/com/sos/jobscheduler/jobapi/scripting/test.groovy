package com.sos.jobscheduler.jobapi.scripting
cnt = 0

public boolean spooler_init() {
    spooler_log.info("spooler_init is called by " + name);
    return true;
}

public boolean spooler_exit() {
    spooler_log.info("spooler_exit is called by " + name);
    return true;
}

public boolean spooler_open() {
    spooler_log.info("spooler_open is called by " + name)
    return true
}

public boolean spooler_close() {
    spooler_log.info("spooler_close is called by " + name)
    return true
}

public boolean spooler_process() {
    if (cnt == 3) {
        return false
    } else {
        cnt++;
        spooler_log.info("spooler_process is called by " + name);
        return true;
    }
}
