package com.fc.v2.service;

import java.util.Date;
import java.util.List;

import com.fc.v2.model.auto.THmfCycleRunLog;
import com.fc.v2.model.auto.THmfCycleSendLog;
import com.fc.v2.model.auto.THmfCycleTask;
import com.fc.v2.model.custom.CycleRunReport;

/**
 * 结息催交单 Service接口（scheduling-job 形状：周期执行，无单条增删改入口）
 *
 * 挑单只看一条：该出手那天到没到（候办 + dueAt &lt;= 此刻，边界含）。
 * 这把尺在查单（{@link #listDue}）与回写两处同一处算，不许各算各的。
 * 止点钉死，不往后挪；能商量的只有“提前几日”那一格，且不动挑单这把尺。
 *
 * @author fuce
 * @date 2026-09-14
 */
public interface ITHmfCycleTaskService {

    /** 按主键回查条目 */
    THmfCycleTask selectTHmfCycleTaskById(Long id);

    /** 该时刻可处理的条目（到期 + 候办；查单与回写同一把尺） */
    List<THmfCycleTask> listDue(Date at);

    /** 执行一次，返回**办结条数**；单条失败跳过继续。签名原样不动。 */
    int runOnce(Date at);

    // --------------------------------------------- 今年钉死的轮次口径（另起的方法名去挂）

    /**
     * 另起的挑单写法：只照“该出手那天到没到”这一条挑（候办 + 止点已到，边界含），
     * 不掺窗口、不掺去向之外的任何东西。窗口按住、办结、催不成由轮次方法另判。
     */
    List<THmfCycleTask> selectDueTasks(Date at);

    /**
     * 定时任务挂这个无参入口：照系统当前时刻跑一轮。
     * 挑单、按住、办结、转催不成、两路人马回执全在这一轮算完再回写，
     * 界面补不进一单也拦不住它少挑一单；三数同本轮明细一次点出。
     */
    CycleRunReport runRound();

    /**
     * 同一套轮次算法，只把“本轮所照的止点时刻”钉死（倒查/核验走这里）。
     * 页面摆的时刻与台账记的对不齐时，以回话里的 runAt 为准。
     */
    CycleRunReport runRound(Date at);

    /** 翻某一轮的台账明细（页面三数照这一批逐条点出，点的就是记的那一档）。 */
    List<THmfCycleRunLog> listRunLogs(int runNo);

    /** 翻某一条各轮的送达台账（站内、短信两路各一笔，撤回归 withdrawal 行另立）。 */
    List<THmfCycleSendLog> listSendLogs(Long itemId);

    /** 最近一轮的轮次号（一轮没跑过为 0）。 */
    int lastRunNo();
}
