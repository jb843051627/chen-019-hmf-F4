package com.fc.v2.hmf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fc.v2.model.auto.THmfArch;
import com.fc.v2.model.auto.THmfContact;
import com.fc.v2.model.auto.THmfCycleRunLog;
import com.fc.v2.model.auto.THmfCycleSendLog;
import com.fc.v2.model.auto.THmfCycleTask;
import com.fc.v2.model.custom.CycleCalendar;
import com.fc.v2.model.custom.CycleRunReport;
import com.fc.v2.service.impl.THmfCycleTaskServiceImpl;

/**
 * 《结息催交轮次口径》逐条核对：
 * 挑单只认止点（含边界那一刻）、止点钉死不后挪、查单回写同一把尺、已办结不再捞；
 * 班内 08:00–20:00 含边界，夜里/周末/法定节假日按住下轮再开口；
 * 封存半月两段全天可出手、同户同段只认头一回落定；
 * 办结两笔（去向+办结时刻）、催办站内短信两路各一笔不顶替；
 * 迁出/联系人一季度空当场催不成且不掐整轮、逾期撤回转催不成但原刻不盖；
 * 三数同本轮台账、空轮回“本轮没有到期条目”、同秒两条各算各的。
 */
class CycleTaskRoundTest {

    private static final int WAIT = 0;
    private static final int DONE = 1;
    private static final int STUCK = 2;

    private static final int KIND_INTEREST = 0;
    private static final int KIND_URGE = 1;

    private static final int ACT_HELD = 0;
    private static final int ACT_DONE = 1;
    private static final int ACT_STUCK = 2;

    private static final int CH_INSITE = 0;
    private static final int CH_SMS = 1;

    private static final int SENT = 0;
    private static final int WITHDRAWN = 1;

    private THmfCycleTaskServiceImpl service;
    private FakeCycleTaskMapper tasks;
    private FakeCycleRunLogMapper runLogs;
    private FakeCycleSendLogMapper sendLogs;
    private FakeArchMapper archs;
    private FakeContactMapper contacts;

    @BeforeEach
    void setUp() throws Exception {
        this.service = new THmfCycleTaskServiceImpl();
        this.tasks = new FakeCycleTaskMapper();
        this.runLogs = new FakeCycleRunLogMapper();
        this.sendLogs = new FakeCycleSendLogMapper();
        this.archs = new FakeArchMapper();
        this.contacts = new FakeContactMapper();
        inject("hmfCycleTaskMapper", tasks);
        inject("hmfCycleRunLogMapper", runLogs);
        inject("hmfCycleSendLogMapper", sendLogs);
        inject("hmfArchMapper", archs);
        inject("hmfContactMapper", contacts);
        // 2026-10-06（周二）钉成法定节假日，其余日子照周一到周五排班。
        inject("cycleCalendar", new CycleCalendar(new HashSet<>(Arrays.asList(
                LocalDate.of(2026, 10, 6)))));

        arch("JZ00", 0); // 在册底册
        arch("JZ01", 1); // 已迁出
        contact("JZ00", "13800000001", "2026-10-01 09:00:00");
    }

    private void inject(String name, Object value) throws Exception {
        Field f = THmfCycleTaskServiceImpl.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private Date t(String text) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").parse(text);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void arch(String siteNo, int status) {
        THmfArch a = new THmfArch();
        a.setSiteNo(siteNo);
        a.setStatus(status);
        a.setDelFlag(0);
        archs.insert(a);
    }

    private void contact(String siteNo, String phone, String fillTime) {
        THmfContact c = new THmfContact();
        c.setSiteNo(siteNo);
        c.setContactName("业委会联系人" + siteNo);
        c.setPhone(phone);
        c.setFillTime(t(fillTime));
        c.setDelFlag(0);
        contacts.insert(c);
    }

    private THmfCycleTask item(String no, int kind, String siteNo, String dueAt) {
        THmfCycleTask r = new THmfCycleTask();
        r.setItemNo(no);
        r.setItemKind(kind);
        r.setSiteNo(siteNo);
        r.setDueAt(t(dueAt));
        r.setAmount(BigDecimal.ZERO); // 默认不提前；要试“提前几天”另开用例
        r.setContent("事由文本-" + no);
        r.setStatus(WAIT);
        r.setDelFlag(0);
        tasks.insert(r);
        return r;
    }

    private THmfCycleTask fresh(Long id) {
        return service.selectTHmfCycleTaskById(id);
    }

    private List<THmfCycleRunLog> logs(int runNo, int action) {
        return service.listRunLogs(runNo).stream()
                .filter(l -> action == l.getAction())
                .collect(Collectors.toList());
    }

    // ---- 挑单：只看止点到没到，边界含，已办结不捞 ----

    @Test
    void selection_onlyDueAt_boundaryInclusive_doneNeverPickedAgain() {
        THmfCycleTask exact = item("JX-1", KIND_INTEREST, "JZ00", "2026-10-02 20:00:00");
        THmfCycleTask notYet = item("JX-2", KIND_INTEREST, "JZ00", "2026-10-02 20:00:01");

        // 查单这把尺：恰好止点那一刻也算到期（边界动手）；晚一秒的不碰
        List<THmfCycleTask> due = service.listDue(t("2026-10-02 20:00:00"));
        assertEquals(1, due.size());
        assertEquals(exact.getId(), due.get(0).getId());

        // 20:00 是班内收口那一刻：轮次里照样办结
        CycleRunReport rep = service.runRound(t("2026-10-02 20:00:00"));
        assertEquals(1, rep.getFinishedCount());
        assertEquals(DONE, fresh(exact.getId()).getStatus().intValue());

        // 已办结的条目不会再被当候办捞进下一轮（哪怕止点早过）
        assertTrue(service.listDue(t("2026-12-01 09:00:00")).stream()
                .noneMatch(r -> r.getId().equals(exact.getId())));
        assertEquals(1, service.listDue(t("2026-12-01 09:00:00")).size(),
                "未到期的后来到期了，也只挑它一条");
    }

    // ---- 提前几天那一格：只催交可提前，结息照止点；止点钉死不挪 ----

    @Test
    void urgeCanOpenEarly_interestStaysPinned_dueDayNeverMoved() {
        // 催交，可提前 3 日开口
        THmfCycleTask urge = item("CJ-EARLY", KIND_URGE, "JZ00", "2026-10-08 09:00:00");
        urge.setAmount(new BigDecimal("3"));
        // 结息，止点钉在周五（10-09）08:00，不提前（提前量只有催交能商量）
        THmfCycleTask interest = item("JX-PIN", KIND_INTEREST, "JZ00", "2026-10-09 08:00:00");

        // 10-05 09:00：催交的开口日（10-08 往回拨 3 天）已到，办结；结息的止点没到，不碰
        CycleRunReport rep = service.runRound(t("2026-10-05 09:00:00"));
        assertEquals(1, rep.getPicked().size());
        assertEquals(DONE, fresh(urge.getId()).getStatus().intValue());
        assertEquals(WAIT, fresh(interest.getId()).getStatus().intValue(), "结息只认钉死的止点，不提前");

        // 周四 21:00（夜里）：催交那条已办结不在场；结息止点在周五，本轮一条都没到期
        CycleRunReport rep2 = service.runRound(t("2026-10-08 21:00:00"));
        assertEquals(0, rep2.getPicked().size());
        assertEquals(0, rep2.getWaitingCount());
        assertEquals(WAIT, fresh(interest.getId()).getStatus().intValue());

        // 周五 08:00 开口：结息止点那一刻到（且没过当天）办结；催交那条早先已办结
        CycleRunReport rep3 = service.runRound(t("2026-10-09 08:00:00"));
        assertEquals(0, rep3.getWaitingCount());
        assertEquals(1, rep3.getFinishedCount());
        assertEquals(DONE, fresh(interest.getId()).getStatus().intValue(),
                "开口边界那一刻动手，且没过止点当天");
        assertNotNull(fresh(interest.getId()).getFinishAt());
    }

    // ---- 办结两笔：去向 + 办结那一刻，缺一不可 ----

    @Test
    void interest_finished_writesStatusAndFinishMomentInSameStroke() {
        THmfCycleTask r = item("JX-OK", KIND_INTEREST, "JZ00", "2026-10-02 09:00:00");
        CycleRunReport rep = service.runRound(t("2026-10-02 10:00:00"));

        assertEquals(1, rep.getFinishedCount());
        assertEquals(0, rep.getWaitingCount());
        assertEquals(0, rep.getStuckCount());
        THmfCycleTask stored = fresh(r.getId());
        assertEquals(DONE, stored.getStatus().intValue());
        assertEquals(t("2026-10-02 10:00:00"), stored.getFinishAt(),
                "办结那一刻与改去向同一笔落");
        THmfCycleRunLog l = logs(rep.getRunNo(), ACT_DONE).get(0);
        assertEquals(t("2026-10-02 10:00:00"), l.getFinishAt(),
                "台账上的办结时刻与主单同源誊一笔");
    }

    // ---- 催办两路：站内、短信各记一笔，谁也不顶谁 ----

    @Test
    void urge_sendsInsiteAndSms_separatelyRecorded_thenFinished() {
        THmfCycleTask r = item("CJ-1", KIND_URGE, "JZ00", "2026-10-02 09:00:00");
        CycleRunReport rep = service.runRound(t("2026-10-02 10:00:00"));

        assertEquals(1, rep.getFinishedCount());
        List<THmfCycleSendLog> sent = service.listSendLogs(r.getId()).stream()
                .filter(s -> s.getAction() == SENT).collect(Collectors.toList());
        assertEquals(2, sent.size(), "物业站内、业委会短信，两路各一笔");
        assertEquals(new HashSet<>(Arrays.asList(CH_INSITE, CH_SMS)),
                sent.stream().map(THmfCycleSendLog::getChannel).collect(Collectors.toSet()));
        assertEquals("物业@JZ00", sent.get(0).getTarget());
        assertEquals("13800000001", sent.get(1).getTarget());
        // 两笔都送的是同一份事由文本
        for (THmfCycleSendLog s : sent) {
            assertEquals("事由文本-CJ-1", s.getText());
        }
        assertEquals(DONE, fresh(r.getId()).getStatus().intValue());
        assertNotNull(fresh(r.getId()).getFinishAt());
    }

    // ---- 撞墙两因：迁出、联系人一栏一个季度没填；当场催不成，行仍翻得到，别行照跑 ----

    @Test
    void movedOutArch_markedStuckOnTheSpot_otherRowsGoOn() {
        THmfCycleTask bad = item("CJ-OUT", KIND_URGE, "JZ01", "2026-10-02 09:00:00");
        THmfCycleTask good = item("JX-GO", KIND_INTEREST, "JZ00", "2026-10-02 09:00:00");

        CycleRunReport rep = service.runRound(t("2026-10-02 10:00:00"));
        assertEquals(1, rep.getStuckCount(), "单条绊住不掐整轮");
        assertEquals(1, rep.getFinishedCount());
        THmfCycleTask stored = fresh(bad.getId());
        assertEquals(STUCK, stored.getStatus().intValue());
        assertTrue(stored.getRemark().contains("已迁走"), "为什么催不成跟进行里");
        assertNotNull(service.selectTHmfCycleTaskById(bad.getId()), "这类条目页面上仍旧翻得到");
        assertEquals(DONE, fresh(good.getId()).getStatus().intValue(), "后面的照旧跑完");
    }

    @Test
    void contactEmptyOrStaleAQuarter_markedStuck() {
        // 联系人栏从没填过
        THmfCycleTask noContact = item("CJ-NC", KIND_URGE, "JZ-NONE", "2026-10-02 09:00:00");
        // 最近填写落在上一个自然季度（Q3），到本轮（Q4）一个季度没填
        contact("JZ-OLD", "13800000002", "2026-07-15 09:00:00");
        arch("JZ-OLD", 0);
        THmfCycleTask stale = item("CJ-OLD", KIND_URGE, "JZ-OLD", "2026-10-02 09:00:00");
        // 季首（10-01 当天）填过的算本季填过，不判催不成
        contact("JZ-FRESH", "13800000003", "2026-10-01 08:00:00");
        arch("JZ-FRESH", 0);
        THmfCycleTask fresh = item("CJ-FR", KIND_URGE, "JZ-FRESH", "2026-10-02 09:00:00");

        CycleRunReport rep = service.runRound(t("2026-10-02 10:00:00"));
        assertEquals(2, rep.getStuckCount());
        assertEquals(STUCK, fresh(noContact.getId()).getStatus().intValue());
        assertTrue(fresh(noContact.getId()).getRemark().contains("从没填过"));
        assertEquals(STUCK, fresh(stale.getId()).getStatus().intValue());
        assertTrue(fresh(stale.getId()).getRemark().contains("一个季度没填过"));
        assertEquals(DONE, fresh(fresh.getId()).getStatus().intValue(), "季首含当日填过的照样催得出去");
    }

    @Test
    void urgeWithBlankPhone_markedStuck_noSmsRowSubstitutes() {
        contact("JZ-NP", null, "2026-10-01 09:00:00");
        arch("JZ-NP", 0);
        THmfCycleTask r = item("CJ-NP", KIND_URGE, "JZ-NP", "2026-10-02 09:00:00");

        CycleRunReport rep = service.runRound(t("2026-10-02 10:00:00"));
        assertEquals(STUCK, fresh(r.getId()).getStatus().intValue());
        assertTrue(service.listSendLogs(r.getId()).isEmpty(), "手机号空，不许拿站内那一路去顶短信");
    }

    // ---- 班外、夜里、周末、法定节假日：先压着，下轮再开口 ----

    @Test
    void nightWeekendHoliday_heldThenDoneNextOpening() {
        // 周五 07:30 天未亮班外到期：同日先压着；08:00 开口仍在当天，办结
        THmfCycleTask night = item("JX-N", KIND_INTEREST, "JZ00", "2026-10-02 07:30:00");
        // 周六到期：压到周一开口时已过了止点那天——撤回转催不成，而不是办结
        THmfCycleTask weekend = item("JX-W", KIND_INTEREST, "JZ00", "2026-10-03 09:00:00");
        // 法定节假日到期：次日开口也已过那天，同样催不成
        THmfCycleTask holiday = item("JX-H", KIND_INTEREST, "JZ00", "2026-10-06 09:00:00");

        // 周五 07:59:59 还差一秒开口：到期也压着
        CycleRunReport r1 = service.runRound(t("2026-10-02 07:59:59"));
        assertEquals(1, r1.getWaitingCount());
        assertEquals(WAIT, fresh(night.getId()).getStatus().intValue(), "主单一字不动");
        assertEquals(ACT_HELD, logs(r1.getRunNo(), ACT_HELD).get(0).getAction().intValue());

        // 周六白天：周末整段班外，照样压
        CycleRunReport r2 = service.runRound(t("2026-10-03 10:00:00"));
        assertEquals(2, r2.getWaitingCount());
        assertEquals(WAIT, fresh(weekend.getId()).getStatus().intValue());

        // 周二虽是工作日但钉成法定节假日：整段不算出手
        CycleRunReport r3 = service.runRound(t("2026-10-06 10:00:00"));
        assertEquals(3, r3.getWaitingCount());
        assertEquals(WAIT, fresh(holiday.getId()).getStatus().intValue());

        // 周五 08:00 开口：夜里那条同日到期未逾期，办结；
        // 周六、节假日两条已过了各自的止点之日，撤回转催不成
        CycleRunReport r4 = service.runRound(t("2026-10-02 08:00:00"));
        assertEquals(DONE, fresh(night.getId()).getStatus().intValue());

        CycleRunReport r5 = service.runRound(t("2026-10-05 08:00:00"));
        assertEquals(STUCK, fresh(weekend.getId()).getStatus().intValue(),
                "压到周一已过了周六那天，撤回转催不成");
        CycleRunReport r6 = service.runRound(t("2026-10-07 08:00:00"));
        assertEquals(STUCK, fresh(holiday.getId()).getStatus().intValue(),
                "压到节假日后开口也已过那天，撤回转催不成");
    }

    @Test
    void workWindowBoundary_08and20bothActionable() {
        THmfCycleTask early = item("JX-E", KIND_INTEREST, "JZ00", "2026-10-02 08:00:00");
        THmfCycleTask late = item("JX-L", KIND_INTEREST, "JZ00", "2026-10-02 20:00:00");
        CycleRunReport rep = service.runRound(t("2026-10-02 08:00:00"));
        assertEquals(1, rep.getFinishedCount(), "08:00:00 那一刻照样动手");
        CycleRunReport rep2 = service.runRound(t("2026-10-02 20:00:00"));
        assertEquals(1, rep2.getFinishedCount(), "20:00:00 收口那一刻也动手");
        assertEquals(DONE, fresh(late.getId()).getStatus().intValue());
        assertEquals(2, rep2.getRunNo());
        assertFalse(early.getFinishAt().after(t("2026-10-02 20:00:00")));
    }

    // ---- 年终封存：两段全天出手，同户同段只认头一回落定 ----

    @Test
    void sealHalfMonth_twoStagesAllDay_secondOfSameHouseholdSameStageRejected() {
        // 另一在册底册 JZ02：12-15 周二 21:00 夜里到期，还没进封存期，照班外按住；
        // 16 日进封存头段时已过了它的止点那天，撤回转催不成。用另一户，别占住 JZ00 的同段坑。
        arch("JZ02", 0);
        contact("JZ02", "13800000009", "2026-12-01 09:00:00");
        THmfCycleTask before = item("JX-B", KIND_INTEREST, "JZ02", "2026-12-15 21:00:00");
        CycleRunReport r0 = service.runRound(t("2026-12-15 21:00:00"));
        assertEquals(WAIT, fresh(before.getId()).getStatus().intValue());

        // 12-16 00:30 进入封存头段，但 15 日那条已过了它的止点那天：撤回转催不成，不在封存期办结
        CycleRunReport r1 = service.runRound(t("2026-12-16 00:30:00"));
        assertEquals(0, r1.getFinishedCount());
        assertEquals(1, r1.getStuckCount());
        assertEquals(STUCK, fresh(before.getId()).getStatus().intValue());
        assertNull(fresh(before.getId()).getFinishAt(), "没真正办结，不写办结时刻");

        // 同户同段同轮挤进来两条：头一条落定，后到的进不了账
        THmfCycleTask a = item("CJ-S1", KIND_URGE, "JZ00", "2026-12-17 02:00:00");
        THmfCycleTask b = item("CJ-S2", KIND_URGE, "JZ00", "2026-12-17 03:00:00");
        CycleRunReport r2 = service.runRound(t("2026-12-17 03:30:00"));
        assertEquals(1, r2.getFinishedCount());
        assertEquals(0, r2.getWaitingCount(), "后到那条进不了本轮账，不计本轮三数");
        assertEquals(DONE, fresh(a.getId()).getStatus().intValue());
        assertEquals(WAIT, fresh(b.getId()).getStatus().intValue(), "前一条原样摆着，后到的进不了账");
        assertEquals(1, service.listRunLogs(r2.getRunNo()).size(),
                "本轮台账只有头一回落定那一条，后到的连台账行都不立");

        // 二段从 12-24 零点那一刻切：同户在二段另算，头段按住过的条不占二段的坑；
        // 止点落在跨段那一刻，条仍在当天、不逾期，办结
        THmfCycleTask c = item("CJ-S3", KIND_URGE, "JZ00", "2026-12-24 00:00:00");
        CycleRunReport r3 = service.runRound(t("2026-12-24 00:00:00"));
        assertEquals(DONE, fresh(c.getId()).getStatus().intValue(), "跨进 24 日零点那一刻即在二段");

        // 二段里同户第二条照样被挡
        THmfCycleTask d = item("CJ-S4", KIND_URGE, "JZ00", "2026-12-25 02:00:00");
        THmfCycleTask e = item("CJ-S5", KIND_URGE, "JZ00", "2026-12-25 03:00:00");
        CycleRunReport r4 = service.runRound(t("2026-12-25 03:30:00"));
        assertEquals(DONE, fresh(d.getId()).getStatus().intValue());
        assertEquals(WAIT, fresh(e.getId()).getStatus().intValue());
    }

    // ---- 逾期未办：撤两路、转催不成；头回定的日子不盖 ----

    @Test
    void overdueUnsent_withdrawBothChannels_thenStuck_originalMomentsKept() {
        THmfCycleTask r = item("CJ-LATE", KIND_URGE, "JZ00", "2026-10-01 18:00:00");
        CycleRunReport rep = service.runRound(t("2026-10-05 09:00:00"));

        assertEquals(1, rep.getStuckCount());
        THmfCycleTask stored = fresh(r.getId());
        assertEquals(STUCK, stored.getStatus().intValue());
        assertNull(stored.getFinishAt(), "真正办结时刻没有就不被后一笔盖出来");
        assertEquals(t("2026-10-01 18:00:00"), stored.getDueAt(), "头回定的止点原样钉着");
        assertTrue(stored.getRemark().contains("撤回"));

        List<THmfCycleSendLog> all = service.listSendLogs(r.getId());
        assertEquals(2, all.size(), "两路撤回各一笔，事后查得着");
        assertEquals(new HashSet<>(Arrays.asList(CH_INSITE, CH_SMS)),
                all.stream().map(THmfCycleSendLog::getChannel).collect(Collectors.toSet()));
        for (THmfCycleSendLog s : all) {
            assertEquals(WITHDRAWN, s.getAction().intValue());
        }
        THmfCycleRunLog l = logs(rep.getRunNo(), ACT_STUCK).get(0);
        assertTrue(l.getDetail().contains("撤回"));
    }

    // ---- 空轮：正常收尾，不是告警 ----

    @Test
    void noDueAtAll_normalFinishMessage_notAnAlert() {
        item("JX-FAR", KIND_INTEREST, "JZ00", "2026-12-31 00:00:00");
        CycleRunReport rep = service.runRound(t("2026-10-02 10:00:00"));
        assertEquals(0, rep.getPicked().size());
        assertEquals(0, rep.getWaitingCount());
        assertEquals(0, rep.getFinishedCount());
        assertEquals(0, rep.getStuckCount());
        assertEquals("本轮没有到期条目", rep.getMessage());
    }

    // ---- 同一秒两条：各算各的，不并不漏 ----

    @Test
    void twoItemsSameSecond_countedAsTwo() {
        THmfCycleTask a = item("JX-A", KIND_INTEREST, "JZ00", "2026-10-02 10:00:00");
        THmfCycleTask b = item("JX-B", KIND_INTEREST, "JZ00", "2026-10-02 10:00:00");
        CycleRunReport rep = service.runRound(t("2026-10-02 10:00:00"));
        assertEquals(2, rep.getPicked().size());
        assertEquals(2, rep.getFinishedCount());
        assertEquals(2, logs(rep.getRunNo(), ACT_DONE).size(), "按两笔各算，不并成一条");
        assertEquals(DONE, fresh(a.getId()).getStatus().intValue());
        assertEquals(DONE, fresh(b.getId()).getStatus().intValue());
    }

    // ---- 三数同本轮台账，逐条点得出 ----

    @Test
    void counts_replayFromRunLog_onePass_sameAsScreen() {
        // 周五 09:00 到期：开口办结
        item("JX-1", KIND_INTEREST, "JZ00", "2026-10-02 09:00:00");
        // 催交迁出户：当场催不成
        item("CJ-1", KIND_URGE, "JZ01", "2026-10-02 09:00:00");
        // 周五 21:00（夜里班外）到期：先按住；周一开口时已过了周五那天，撤回转催不成
        item("JX-2", KIND_INTEREST, "JZ00", "2026-10-02 21:00:00");
        // 在班内 09:00 先扫一轮：结息办结、迁出催办当场催不成、夜里那条按住
        CycleRunReport rep = service.runRound(t("2026-10-02 09:00:00"));

        // 班内轮屏上：办结 1、催不成 1（夜里那条本轮没被窗口放进来，按住 0 条）
        assertEquals(0, rep.getWaitingCount());
        assertEquals(1, rep.getFinishedCount());
        assertEquals(1, rep.getStuckCount());
        assertEquals(2, service.listRunLogs(rep.getRunNo()).size());

        // 夜里 21:00 再扫一轮：那条到期但在班外，三数变 1/0/0
        CycleRunReport night = service.runRound(t("2026-10-02 21:00:00"));
        assertEquals(1, night.getWaitingCount());
        assertEquals(0, night.getFinishedCount());
        assertEquals(0, night.getStuckCount());

        // 下一轮周一开口：夜里那条已过了周五那天，撤回转催不成（三数随轮次重算，不分两回去取）
        CycleRunReport next = service.runRound(t("2026-10-05 08:00:00"));
        assertEquals(0, next.getWaitingCount());
        assertEquals(0, next.getFinishedCount());
        assertEquals(1, next.getStuckCount());

        // 档里照班内这轮 runNo 一条一条点：办结 1、催不成 1，对得上同一批条目
        int runNo = rep.getRunNo();
        List<THmfCycleRunLog> logs = service.listRunLogs(runNo);
        assertEquals(2, logs.size());
        List<Long> heldIds = logs(rep.getRunNo(), ACT_HELD).stream()
                .map(THmfCycleRunLog::getItemId).collect(Collectors.toList());
        List<Long> doneIds = logs(rep.getRunNo(), ACT_DONE).stream()
                .map(THmfCycleRunLog::getItemId).collect(Collectors.toList());
        List<Long> stuckIds = logs(rep.getRunNo(), ACT_STUCK).stream()
                .map(THmfCycleRunLog::getItemId).collect(Collectors.toList());
        assertEquals(rep.getWaiting().stream().map(THmfCycleTask::getId).collect(Collectors.toList()),
                new ArrayList<>(heldIds), "班内轮没有按住条目，两边都是空");
        assertEquals(rep.getFinished().stream().map(THmfCycleTask::getId).collect(Collectors.toList()),
                new ArrayList<>(doneIds));
        assertEquals(rep.getStuck().stream().map(THmfCycleTask::getId).collect(Collectors.toList()),
                new ArrayList<>(stuckIds));

        // 旧签名：夜里那轮只有按住，没有新增办结，回话 0
        assertEquals(0, service.runOnce(t("2026-10-02 21:00:00")));
    }
}
