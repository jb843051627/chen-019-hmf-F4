package com.fc.v2.service.impl;

import java.text.SimpleDateFormat;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.THmfArchMapper;
import com.fc.v2.mapper.auto.THmfContactMapper;
import com.fc.v2.mapper.auto.THmfCycleRunLogMapper;
import com.fc.v2.mapper.auto.THmfCycleSendLogMapper;
import com.fc.v2.mapper.auto.THmfCycleTaskMapper;
import com.fc.v2.model.auto.THmfArch;
import com.fc.v2.model.auto.THmfContact;
import com.fc.v2.model.auto.THmfCycleRunLog;
import com.fc.v2.model.auto.THmfCycleSendLog;
import com.fc.v2.model.auto.THmfCycleTask;
import com.fc.v2.model.custom.CycleCalendar;
import com.fc.v2.model.custom.CycleRunReport;
import com.fc.v2.service.ITHmfCycleTaskService;

/**
 * 结息催交单 Service业务层处理（scheduling-job 形状：周期执行）
 *
 * 今年钉死的口径全在 {@link #runRound(Date)} 这一趟：
 *
 * 挑单只看一条——该出手那天到没到：候办(0)且 dueAt &lt;= 此刻（止点边界那一刻照样动手）。
 * 这把尺 {@link #isDue} 在查单（{@link #listDue}）与回写两处同源，不各算各的；
 * 止点钉死，不为凑一批往后挪，“提前几日(amount)”只决定催办时能不能先开口，不进挑单这把尺。
 *
 * 时段另一层：一天割成班内（工作日 08:00–20:00，法定节假日除外，含两头那一刻）与班外，
 * 夜里加法定节假日整段不算出手时间，已到期也先按住等下一轮；
 * 年终封存那半个月单另起两段（12-16 00:00 起、12-24 零点切二段），段内全天可出手；
 * 那半个月同一户同一段一个轮次只认头一次落定，同一天后到的进不了账，前一条原样摆着。
 *
 * 办结落两笔：去向候办→已办结，同一时刻把办结时刻(finish_at)写上，缺一笔不算完。
 * 催办发两处：物业走站内消息、业委会联系人走手机短信，两路各记一笔，缺一路不算办结，
 * 不许拿手机那一路顶站内那一路；过了那天还没办出去的，撤回两路各立撤回行并转催不成，
 * 撤回事后查得着，头回定的止点、办结时刻不被后一笔盖掉。
 *
 * 没开单就撞墙：户已从底册迁走、或催交户联系人栏一个季度没填过，当场判催不成、缘由记行里，
 * 条目页面仍翻得到；单条绊住不掐整轮。已办结的条目不会再被当候办捞进下一轮。
 *
 * 页面三个数（候办/已办结/催不成）只报本轮，且照轮次台账逐条点得出，看屏与点档同一次算；
 * 一条该动的都没挑着，明说“本轮没有到期条目”，这是正常收尾不是告警。
 * 同一秒挤进来两条按两笔各算，不并不漏。
 *
 * @author fuce
 * @date 2026-09-14
 */
@Service
public class THmfCycleTaskServiceImpl implements ITHmfCycleTaskService {

    private static final DateTimeFormatter WALL_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    /** 条目情形 */
    private static final int STATUS_WAIT = 0;
    private static final int STATUS_DONE = 1;
    private static final int STATUS_FAIL = 2;

    /** 事由 */
    private static final int KIND_INTEREST = 0;
    private static final int KIND_URGE = 1;

    /** 轮次台账：本轮落定 */
    private static final int ACT_HELD = 0;
    private static final int ACT_DONE = 1;
    private static final int ACT_STUCK = 2;

    /** 送达路：物业站内消息／业委会联系人手机短信 */
    private static final int CH_INSITE = 0;
    private static final int CH_SMS = 1;

    /** 送达台账本笔 */
    private static final int SEND_SENT = 0;
    private static final int SEND_WITHDRAWN = 1;

    /** 底册情形 */
    private static final int ARCH_MOVED_OUT = 1;

    @javax.annotation.Resource
    private THmfCycleTaskMapper hmfCycleTaskMapper;

    @javax.annotation.Resource
    private THmfCycleRunLogMapper hmfCycleRunLogMapper;

    @javax.annotation.Resource
    private THmfCycleSendLogMapper hmfCycleSendLogMapper;

    @javax.annotation.Resource
    private THmfArchMapper hmfArchMapper;

    @javax.annotation.Resource
    private THmfContactMapper hmfContactMapper;

    /** 值班日历（测试可另注一份带节假日的；不注时按无节假日的常班表走）。 */
    @javax.annotation.Resource
    private CycleCalendar cycleCalendar;

    /**
     * 本轮封存期已“落定”的坑，粒度为 户+段+该条止点之日：封存期里同一户同一段、
     * 止点钉死在同一天的两条，只认头一次落定（按住/办结/催不成），同一天后到的那条
     * 连台账都不立、前一条原样摆着。只在本轮内生效，轮初清空——止点不在同一天的条
     * （哪怕同户同段、隔天才来）不算“同一天来第二次”；上一轮按住的条隔天重扫也照常判。
     */
    private Set<String> sealDecidedKeys = new HashSet<>();

    // ---------------------------------------------------------------- 旧签名（原样不动）

    @Override
    public THmfCycleTask selectTHmfCycleTaskById(Long id) {
        return this.hmfCycleTaskMapper.selectById(id);
    }

    /**
     * 时段比较统一走墙钟字符串（yyyy-MM-dd HH:mm:ss）：JDBC serverTimezone 与本机时区不对称，
     * 直接拿 java.util.Date 作参数会整体偏移，使“恰好到期”这类边界用例错判。
     */
    private static String ts(Date d) {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(d);
    }

    /** 墙钟字符串转 LocalDateTime：挑单、窗口同一把尺，都从这条墙钟线过。 */
    private static LocalDateTime wall(Date d) {
        return LocalDateTime.parse(ts(d), WALL_FMT);
    }

    /**
     * 唯一的一把尺：候办条目、该开口的日子已到（边界那一刻含）。
     * 结息的开口日就是钉死的止点 dueAt；催交可照“提前几天(amount)”那一格先开口，
     * 开口日为止点往回拨 amount 个整天——止点本身仍钉死，逾期与否仍照止点之日判，
     * 能商量的只有提前量。查单与回写都只认这句话，不夹窗口，更不许把止点往后挪。
     */
    private boolean isDue(THmfCycleTask r, Date at) {
        if (r == null || r.getDueAt() == null || at == null
                || r.getStatus() == null || r.getStatus() != STATUS_WAIT
                || (r.getDelFlag() != null && r.getDelFlag() != 0)) {
            return false;
        }
        return ts(openAt(r)).compareTo(ts(at)) <= 0;
    }

    /**
     * 该开口那一刻：结息认止点；催交从止点往回拨“提前几天”（按整天拨，amount 非正或空当作不提前）。
     * 止点钉死、只提前量可商量——开口提前不等于止点提前，过没过“那天”仍只照 dueAt 判。
     */
    private static Date openAt(THmfCycleTask r) {
        int days = r.getAmount() == null ? 0 : r.getAmount().intValue();
        if (r.getItemKind() != null && r.getItemKind() == KIND_URGE && days > 0) {
            return new Date(r.getDueAt().getTime() - days * 24L * 60 * 60 * 1000);
        }
        return r.getDueAt();
    }

    @Override
    public List<THmfCycleTask> listDue(Date at) {
        List<THmfCycleTask> due = new ArrayList<>();
        if (at == null) {
            return due;
        }
        for (THmfCycleTask r : this.hmfCycleTaskMapper.selectList(new QueryWrapper<THmfCycleTask>()
                .eq("del_flag", 0).orderByAsc("due_at").orderByAsc("id"))) {
            if (isDue(r, at)) {
                due.add(r);
            }
        }
        return due;
    }

    /** 旧入口：跑一轮，办结条数回话。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public int runOnce(Date at) {
        return runRound(at).getFinishedCount();
    }

    // ---------------------------------------------------------------- 今年的轮次

    /** 另起的挑单写法：与 {@link #listDue} 同一把尺，只挑到期候办，名字另挂，旧法不动。 */
    @Override
    public List<THmfCycleTask> selectDueTasks(Date at) {
        return listDue(at);
    }

    /** 定时任务挂的无参入口：照系统当前时刻跑一轮。 */
    @Override
    @Transactional(rollbackFor = Exception.class)
    public CycleRunReport runRound() {
        return runRound(null);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public CycleRunReport runRound(Date at) {
        Date runAt = at == null ? nowTime() : at;
        LocalDateTime wallAt = wall(runAt);
        int runNo = lastRunNo() + 1;

        CycleRunReport report = new CycleRunReport();
        report.setRunNo(runNo);
        report.setRunAt(runAt);
        report.setPicked(selectDueTasks(runAt));

        // 封存期同户同段当天本轮已落定过的：户+段+当天 作一把钥匙，只认头一回落定。
        this.sealDecidedKeys.clear();
        CycleCalendar cal = calendar();

        for (THmfCycleTask r : report.getPicked()) {
            // 单条绊住不掐整轮：这一条出什么岔子都自己兜住，后面的条目照跑到底。
            try {
                processOne(r, runNo, runAt, wallAt, cal, sealDecidedKeys);
            } catch (RuntimeException ex) {
                // 连判定都没能落账的兜底：记催不成，不连累本轮别的条目。
                forceStuck(r, runNo, runAt, "本条办理时绊住：" + ex.getMessage());
            }
        }

        // 页面三数与屏上这一回同一次算：不照内存里的单子回数，照轮次台账逐条重放。
        // 封存期后到那条不立明细，前面按住行也仍是这一户本轮唯一一笔——取每单最后一条
        // （同户同段按住后又来第二次时，第二次不立账，库里留着的自然是头一条）。
        Map<Long, THmfCycleTask> byId = new LinkedHashMap<>();
        for (THmfCycleTask r : report.getPicked()) {
            byId.put(r.getId(), r);
        }
        Map<Long, Integer> finalAction = new LinkedHashMap<>();
        for (THmfCycleRunLog l : listRunLogs(runNo)) {
            if (byId.containsKey(l.getItemId())) {
                finalAction.put(l.getItemId(), l.getAction());
            }
        }
        List<THmfCycleTask> waiting = new ArrayList<>();
        List<THmfCycleTask> finished = new ArrayList<>();
        List<THmfCycleTask> stuck = new ArrayList<>();
        for (Map.Entry<Long, THmfCycleTask> e : byId.entrySet()) {
            Integer a = finalAction.get(e.getKey());
            if (a == null) {
                continue;
            }
            if (a == ACT_DONE) {
                finished.add(e.getValue());
            } else if (a == ACT_STUCK) {
                stuck.add(e.getValue());
            } else {
                waiting.add(e.getValue());
            }
        }
        report.setWaiting(waiting);
        report.setFinished(finished);
        report.setStuck(stuck);
        report.setMessage(buildMessage(report));
        return report;
    }

    /** 一条到期条目走完这一轮：按住、办结、催不成三选一。 */
    private void processOne(THmfCycleTask r, int runNo, Date runAt, LocalDateTime wallAt,
                           CycleCalendar cal, Set<String> sealDecidedKeys) {
        // 年终封存两段：同户同段当天只认第一次“落定”的结果（按住也算落定）；
        // 第一条只是被班外窗口按住的，也占住当天的坑，后到的条照样不立账。
        String sealKey = null;
        if (cal.inSealWindow(wallAt)) {
            sealKey = sealKey(r.getSiteNo(), cal.inSealFirstStage(wallAt), r.getDueAt());
            if (sealDecidedKeys.contains(sealKey)) {
                return;
            }
        }

        // 时段这层先过（封存两段内全天可出手，不跟班内班外、节假日，故封存期走不到这道按住）：
        // 班外、夜里、法定节假日、休息日，手里有已到期的也先压着，等下一轮开口再说。
        if (!cal.isActionable(wallAt)) {
            String why;
            if (cal.isLegalHoliday(wallAt.toLocalDate())) {
                why = "法定节假日整段不算出手时间，先压着等下一轮开口";
            } else if (cal.isWeekend(wallAt.toLocalDate())) {
                why = "休息日班外，已到期也先压着，等下一轮开口";
            } else {
                why = "夜里班外（20:00后到次日08:00前）不算出手时间，先压着";
            }
            hold(r, runNo, runAt, why, sealKey);
            return;
        }

        // 没开单先撞墙：已迁走、或催交联系人一个季度没填（或手机号空着）——当场判催不成，
        // 缘由记行里，页面仍翻得到，后面的条目照旧跑完。
        String wall = wallReason(r, wallAt);
        if (wall != null) {
            stuck(r, runNo, runAt, wall, sealKey);
            return;
        }

        // 过了那天还没办出去：撤回两路、转催不成。撤回笔事后查得着，
        // 头回定的止点、真正办结时刻都不被这一笔盖掉。
        if (isOverdue(r, wallAt.toLocalDate())) {
            String reason = "已过止点之日（" + ts(r.getDueAt()) + "）尚未办出，撤回并转催不成";
            if (isUrge(r)) {
                sendOne(r, runNo, runAt, CH_INSITE, SEND_WITHDRAWN,
                        insiteTarget(r), "撤回催办：" + reason);
                sendOne(r, runNo, runAt, CH_SMS, SEND_WITHDRAWN,
                        smsTarget(r), "撤回催办：" + reason);
            }
            stuck(r, runNo, runAt, reason, sealKey);
            return;
        }

        if (isUrge(r)) {
            // 催办发两处，各记各的：物业站内、业委会联系人短信，缺一路都不算办结，
            // 更不许拿短信那一路顶站内那一路。
            sendOne(r, runNo, runAt, CH_INSITE, SEND_SENT, insiteTarget(r), sendText(r));
            sendOne(r, runNo, runAt, CH_SMS, SEND_SENT, smsTarget(r), sendText(r));
        }
        complete(r, runNo, runAt, sealKey);
    }

    // ---------------------------------------------------------------- 三种落定

    /** 按住：主单一字不动，只添轮次台账一行（候办，等下一轮）。封存期按住即占“户+段+当天”的坑。 */
    private void hold(THmfCycleTask r, int runNo, Date runAt, String reason, String sealKey) {
        addRunLog(r, runNo, runAt, ACT_HELD, null, reason);
        settleSeal(sealKey);
    }

    /** 催不成：主单去向改 2、缘由落进行里（不盖止点、不盖办结时刻），台账另立。封存期占同段坑。 */
    private void stuck(THmfCycleTask r, int runNo, Date runAt, String reason, String sealKey) {
        r.setStatus(STATUS_FAIL);
        appendRemark(r, reason);
        this.hmfCycleTaskMapper.updateById(r);
        addRunLog(r, runNo, runAt, ACT_STUCK, null, reason);
        settleSeal(sealKey);
    }

    /** 兜底：条目自身绊住到连台账都没立成时，补一条催不成，别掐住整轮。 */
    private void forceStuck(THmfCycleTask r, int runNo, Date runAt, String reason) {
        try {
            stuck(r, runNo, runAt, reason, null);
        } catch (RuntimeException ignore) {
            try {
                addRunLog(r, runNo, runAt, ACT_STUCK, null, reason);
            } catch (RuntimeException ignore2) {
                // 台账也写不进时不再纠缠，保整轮跑得完。
            }
        }
    }

    /** 办结两笔同源：去向改已办结 + 办结那一刻同一刻写上，缺一笔都不算完。封存期占同段坑。 */
    private void complete(THmfCycleTask r, int runNo, Date runAt, String sealKey) {
        r.setStatus(STATUS_DONE);
        r.setFinishAt(runAt);
        this.hmfCycleTaskMapper.updateById(r);
        addRunLog(r, runNo, runAt, ACT_DONE, runAt,
                (isUrge(r) ? "催办两处（站内消息、手机短信）均送达" : "年度结息") + "，办结");
        settleSeal(sealKey);
    }

    /** 封存期落定（办结/催不成）后占住同户同段的坑，按住不占、只落定的占。 */
    private void settleSeal(String sealKey) {
        if (sealKey != null) {
            sealDecidedKeys.add(sealKey);
        }
    }
    /** 一路送达一笔；撤回走 action=SEND_WITHDRAWN 另立一行，旧送达行原样留着。 */
    private void sendOne(THmfCycleTask r, int runNo, Date runAt, int channel, int action,
                        String target, String text) {
        THmfCycleSendLog s = new THmfCycleSendLog();
        s.setRunNo(runNo);
        s.setItemId(r.getId());
        s.setChannel(channel);
        s.setAction(action);
        s.setSentAt(runAt);
        s.setTarget(target);
        s.setText(text);
        s.setDelFlag(0);
        s.setCreateTime(runAt);
        this.hmfCycleSendLogMapper.insert(s);
    }

    private void addRunLog(THmfCycleTask r, int runNo, Date runAt, int action, Date finishAt, String detail) {
        THmfCycleRunLog l = new THmfCycleRunLog();
        l.setRunNo(runNo);
        l.setRunAt(runAt);
        l.setItemId(r.getId());
        l.setItemNo(r.getItemNo());
        l.setAction(action);
        l.setFinishAt(finishAt);
        l.setDetail(detail);
        l.setDelFlag(0);
        l.setCreateTime(runAt);
        this.hmfCycleRunLogMapper.insert(l);
    }

    // ---------------------------------------------------------------- 撞墙两因

    private String wallReason(THmfCycleTask r, LocalDateTime wallAt) {
        String siteNo = r.getSiteNo() == null ? null : r.getSiteNo().trim();
        if (!isBlank(siteNo)) {
            List<THmfArch> archs = this.hmfArchMapper.selectList(new QueryWrapper<THmfArch>()
                    .eq("site_no", siteNo).eq("del_flag", 0));
            for (THmfArch a : archs) {
                if (a.getStatus() != null && a.getStatus() == ARCH_MOVED_OUT) {
                    return "分户底册" + siteNo + "已迁走，本单当场判催不成";
                }
            }
        }
        if (isUrge(r)) {
            THmfContact c = contactOf(siteNo);
            if (c == null) {
                return "业委会联系人栏空着（从没填过），催办发不出去，当场判催不成";
            }
            if (isBlank(c.getPhone())) {
                return "业委会联系人手机短信号空着，短信那一路发不出去，当场判催不成";
            }
            if (isStaleAQuarter(c.getFillTime(), wallAt.toLocalDate())) {
                return "业委会联系人栏一个季度没填过（最近填写："
                        + (c.getFillTime() == null ? "空" : ts(c.getFillTime())) + "），当场判催不成";
            }
        }
        return null;
    }

    private THmfContact contactOf(String siteNo) {
        if (isBlank(siteNo)) {
            return null;
        }
        List<THmfContact> list = this.hmfContactMapper.selectList(new QueryWrapper<THmfContact>()
                .eq("site_no", siteNo).eq("del_flag", 0)
                .orderByDesc("fill_time").orderByDesc("id"));
        return list.isEmpty() ? null : list.get(0);
    }

    /** 联系人栏一个季度没填过：本季度（自然季）内没填即作数，季首那一刻含在本季。 */
    private static boolean isStaleAQuarter(Date fillTime, LocalDate runDate) {
        if (fillTime == null) {
            return true;
        }
        LocalDate fill = wall(fillTime).toLocalDate();
        int qStartMonth = (runDate.getMonthValue() - 1) / 3 * 3 + 1;
        LocalDate quarterStart = LocalDate.of(runDate.getYear(), qStartMonth, 1);
        return fill.isBefore(quarterStart);
    }

    /** 过了那天：照自然日比，止点当天的任意时刻都还算当天（含夜里被压到次日开口时的边界）。 */
    private static boolean isOverdue(THmfCycleTask r, LocalDate runDate) {
        return wall(r.getDueAt()).toLocalDate().isBefore(runDate);
    }

    // ---------------------------------------------------------------- 回话与翻档

    @Override
    public List<THmfCycleRunLog> listRunLogs(int runNo) {
        return this.hmfCycleRunLogMapper.selectList(new QueryWrapper<THmfCycleRunLog>()
                .eq("run_no", runNo).eq("del_flag", 0).orderByAsc("id"));
    }

    @Override
    public List<THmfCycleSendLog> listSendLogs(Long itemId) {
        if (itemId == null) {
            return new ArrayList<>();
        }
        return this.hmfCycleSendLogMapper.selectList(new QueryWrapper<THmfCycleSendLog>()
                .eq("item_id", itemId).eq("del_flag", 0).orderByAsc("run_no").orderByAsc("id"));
    }

    @Override
    public int lastRunNo() {
        int max = 0;
        for (THmfCycleRunLog l : this.hmfCycleRunLogMapper.selectList(new QueryWrapper<THmfCycleRunLog>()
                .eq("del_flag", 0))) {
            if (l.getRunNo() != null && l.getRunNo() > max) {
                max = l.getRunNo();
            }
        }
        return max;
    }

    private String buildMessage(CycleRunReport report) {
        if (report.getPicked().isEmpty()) {
            // 一条该动的都没挑着：明明白白这句，正常收尾，不当失败告警追人。
            return "本轮没有到期条目";
        }
        StringBuilder sb = new StringBuilder();
        sb.append("本轮到期").append(report.getPicked().size()).append("条：")
                .append("等着办").append(report.getWaitingCount()).append("、")
                .append("已经办完").append(report.getFinishedCount()).append("、")
                .append("催不动").append(report.getStuckCount());
        if (!report.getFinished().isEmpty() || !report.getStuck().isEmpty()) {
            sb.append("；三数照本轮轮次台账逐条点出，看屏与点档同一次算");
        }
        return sb.toString();
    }

    // ---------------------------------------------------------------- 零碎帮手

    private static boolean isUrge(THmfCycleTask r) {
        return r.getItemKind() != null && r.getItemKind() == KIND_URGE;
    }

    /** 物业那一路：站内消息送到本户底册的物业值班口。 */
    private static String insiteTarget(THmfCycleTask r) {
        return "物业@" + (r.getSiteNo() == null ? "" : r.getSiteNo().trim());
    }

    /** 业委会联系人那一路：手机短信号（联系人栏空着也照记一笔，号空即发不到）。 */
    private String smsTarget(THmfCycleTask r) {
        THmfContact c = contactOf(r.getSiteNo() == null ? null : r.getSiteNo().trim());
        return c == null ? null : (isBlank(c.getPhone()) ? null : c.getPhone().trim());
    }

    private static String sendText(THmfCycleTask r) {
        return r.getContent();
    }

    /** 坑键：户 + 段 + 该条钉死的止点之日（同一天照止点日认，不照扫描日）。 */
    private static String sealKey(String siteNo, boolean firstStage, Date dueAt) {
        String day;
        if (dueAt == null) {
            day = "";
        } else {
            java.time.LocalDate d = wall(dueAt).toLocalDate();
            day = d.toString();
        }
        return (siteNo == null ? "" : siteNo.trim()) + "#" + (firstStage ? "s1" : "s2") + "#" + day;
    }

    private static void appendRemark(THmfCycleTask r, String line) {
        String old = r.getRemark();
        r.setRemark(isBlank(old) ? line : old + "\n" + line);
    }

    private CycleCalendar calendar() {
        return this.cycleCalendar == null ? new CycleCalendar() : this.cycleCalendar;
    }

    /** 记账时刻：生产取当前；留口子给定点核验。 */
    protected Date nowTime() {
        return new Date();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
