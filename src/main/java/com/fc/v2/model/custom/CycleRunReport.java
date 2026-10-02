package com.fc.v2.model.custom;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fc.v2.model.auto.THmfCycleTask;

/**
 * 一轮扫描的回话：挑中哪些单、各条怎么办成或怎么按住，全凭服务层定时任务当轮算完再回写。
 *
 * 页面只报本轮这一次的三个数——候办（按住未出手）、已办结、催不成；
 * 三个数都能从轮次台账按 runNo 一条一条点出来，看屏那回与点档那回是同一次算的。
 * 一条该动的都没挑着，message 明明白白回“本轮没有到期条目”，这是正常收尾，不是失败告警。
 *
 * @author fuce
 * @date 2026-10-02
 */
public class CycleRunReport implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 轮次号：轮次台账里这一轮的那一号，点档照它点。 */
    private int runNo;

    /** 本轮扫描所照的止点时刻（页面时刻与它不齐时，以它为准） */
    private java.util.Date runAt;

    /** 本轮挑中、照日子够上止点的条目（含被窗口按住、撞墙判死、逾期撤回的，都在这批里） */
    private List<THmfCycleTask> picked = new ArrayList<>();

    /** 本轮落定后仍在候办的：挑中了但被班外/夜里/节假日按住，下一轮再开口 */
    private List<THmfCycleTask> waiting = new ArrayList<>();

    /** 本轮办结的条目（去向改已办结 + 办结时刻，两笔齐才算） */
    private List<THmfCycleTask> finished = new ArrayList<>();

    /** 本轮判催不成的条目：迁出/联系人栏一季度空，或过了止点还没办出去撤回转死 */
    private List<THmfCycleTask> stuck = new ArrayList<>();

    /** 给看屏人的那一句话；一轮空走时是“本轮没有到期条目” */
    private String message;

    public int getRunNo() {
        return runNo;
    }

    public void setRunNo(int runNo) {
        this.runNo = runNo;
    }

    public java.util.Date getRunAt() {
        return runAt;
    }

    public void setRunAt(java.util.Date runAt) {
        this.runAt = runAt;
    }

    public List<THmfCycleTask> getPicked() {
        return picked;
    }

    public void setPicked(List<THmfCycleTask> picked) {
        this.picked = picked == null ? Collections.emptyList() : picked;
    }

    public List<THmfCycleTask> getWaiting() {
        return waiting;
    }

    public void setWaiting(List<THmfCycleTask> waiting) {
        this.waiting = waiting == null ? Collections.emptyList() : waiting;
    }

    public List<THmfCycleTask> getFinished() {
        return finished;
    }

    public void setFinished(List<THmfCycleTask> finished) {
        this.finished = finished == null ? Collections.emptyList() : finished;
    }

    public List<THmfCycleTask> getStuck() {
        return stuck;
    }

    public void setStuck(List<THmfCycleTask> stuck) {
        this.stuck = stuck == null ? Collections.emptyList() : stuck;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    /** 三个数同出这一轮的明细，不分两回去取。 */
    public int getWaitingCount() {
        return waiting.size();
    }

    public int getFinishedCount() {
        return finished.size();
    }

    public int getStuckCount() {
        return stuck.size();
    }
}
