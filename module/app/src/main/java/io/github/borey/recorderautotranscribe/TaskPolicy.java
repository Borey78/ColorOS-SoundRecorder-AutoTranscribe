package io.github.borey.recorderautotranscribe;

final class TaskPolicy {
    static boolean summaryHasResult(int contentLength,int userStop) {
        return contentLength>0 && userStop!=1;
    }
    static boolean summarySatisfied(boolean summaryDone,boolean summarySkipped) {
        return summaryDone || summarySkipped;
    }
    static boolean isDoneSatisfied(boolean complete, boolean summaryDone, boolean summarySkipped, boolean aiSummaryEnabled) {
        return (complete && summarySatisfied(summaryDone, summarySkipped)) || (complete && !aiSummaryEnabled);
    }
    static boolean canRecover(boolean done,boolean active,boolean sameSession,int attempts) {
        return !done && !active && !sameSession && attempts < 2;
    }
    static boolean canStart(boolean enabled, boolean allowed, boolean done, boolean active,
                            boolean attempted, int running, int limit) {
        return enabled && allowed && !done && !active && !attempted && running < limit;
    }
    static String transcription(boolean complete, boolean hidden, boolean active, int upload, int convert) {
        if (active) return upload == 1002 || convert == 1002 ? "上传中" : convert == 1008 ? "上传完成，云端转写中" : "转写中";
        if (complete) return hidden ? "已转写（原应用隐藏）" : "已完成";
        if (upload == 4001 || convert == 4002) return "失败：无网络";
        if (upload == 5002 || upload == 5005) return "不支持的录音时长";
        if (upload == 5001) return "不支持的文件格式";
        if (upload == 5003) return "文件过大";
        if (convert == 2005) return "失败：转写超时";
        if (upload == 1009 || convert == 2003 || convert == 2006 || upload >= 5000 || convert >= 6000)
            return "失败（上传 " + upload + " / 转写 " + convert + "）";
        return "待转写";
    }
}
