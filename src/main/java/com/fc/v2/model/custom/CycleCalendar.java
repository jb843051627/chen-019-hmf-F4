package com.fc.v2.model.custom;

import java.io.Serializable;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

import org.springframework.stereotype.Component;

/**
 * 值班表割出来的出手时段（今年钉死的口径）：
 *
 * 平常日子割两截——班内为工作日（法定节假日除外）08:00:00 到 20:00:00，起止那一刻都算班内；
 * 夜里（20:00 后到次日 08:00 前）、整天落在法定节假日上、以及休息日，整段不算出手时间：
 * 手里有已到期的也先压着，等下一轮开口。
 *
 * 年终封存那半个月（12-16 起到 12-31 止，两头当日含）不照班内班外分，单另起两段：
 * 头段 12-16 00:00 到 12-23 24:00（含跨进 24 日零点那一刻），二段 12-24 00:00 到 12-31 24:00，
 * 段内全天可出手，夜间、节假日也照出。12-16 00:00 之前仍照常日分法。
 *
 * @author fuce
 * @date 2026-10-02
 */
@Component
public class CycleCalendar implements Serializable {
    private static final long serialVersionUID = 1L;

    /** 班内开口那一刻（含此刻） */
    public static final int WORK_START_HOUR = 8;
    /** 班内收口那一刻（含此刻；落在边界那一刻照样动手） */
    public static final int WORK_END_HOUR = 20;

    /** 年终封存起月日（含当日） */
    public static final int SEAL_FROM_MONTH = 12;
    public static final int SEAL_FROM_DAY = 16;
    /** 头段止：12-23 过完，跨入 12-24 零点那一刻即进二段 */
    public static final int SEAL_FIRST_STAGE_END_DAY = 24;
    /** 年终封存止：12-31 过完 */
    public static final int SEAL_TO_MONTH = 12;
    public static final int SEAL_TO_DAY = 31;

    private final Set<LocalDate> legalHolidays;

    public CycleCalendar() {
        this(Collections.<LocalDate>emptySet());
    }

    public CycleCalendar(Set<LocalDate> legalHolidays) {
        this.legalHolidays = legalHolidays == null
                ? Collections.<LocalDate>emptySet() : new HashSet<>(legalHolidays);
    }

    /** 法定节假日册：值班表认这一份，不再另猜周末调休。 */
    public static CycleCalendar withHolidays(Collection<LocalDate> holidays) {
        return new CycleCalendar(holidays == null ? null : new HashSet<>(holidays));
    }

    /**
     * 这一刻许不许出手。封存期两段内全天可出手（不跟班内班外、不看节假日）；
     * 其余日子只认工作日 08:00–20:00（含两头那一刻），夜里、法定节假日、休息日整段压住。
     */
    public boolean isActionable(LocalDateTime t) {
        if (t == null) {
            return false;
        }
        if (inSealWindow(t)) {
            return true;
        }
        LocalDate d = t.toLocalDate();
        if (isLegalHoliday(d) || isWeekend(d)) {
            return false;
        }
        int h = t.getHour();
        int mi = t.getMinute();
        int s = t.getSecond();
        int nowSec = h * 3600 + mi * 60 + s;
        return nowSec >= WORK_START_HOUR * 3600 && nowSec <= WORK_END_HOUR * 3600;
    }

    /**
     * 落在年终封存期里没有（12-16 00:00 起到 12-31 24:00 止，两头当日含；16 日零点前照常日分法）。
     * 两段都在这半个月里，段内全天可出手，只是后到的同户第二条进不了账。
     */
    public boolean inSealWindow(LocalDateTime t) {
        if (t == null) {
            return false;
        }
        LocalDate d = t.toLocalDate();
        return d.getMonthValue() == SEAL_FROM_MONTH
                && d.getDayOfMonth() >= SEAL_FROM_DAY
                && d.getDayOfMonth() <= SEAL_TO_DAY;
    }

    /** 落在封存头段没有：12-16 00:00 起至 12-23 24:00 止（12-24 00:00 那一刻即切二段）。 */
    public boolean inSealFirstStage(LocalDateTime t) {
        if (!inSealWindow(t)) {
            return false;
        }
        return t.getDayOfMonth() < SEAL_FIRST_STAGE_END_DAY;
    }

    /** 是否法定节假日（整天不算出手时间）。 */
    public boolean isLegalHoliday(LocalDate d) {
        return d != null && legalHolidays.contains(d);
    }

    /** 休息日：值班表不排班，白天也算班外（封存两段除外）。 */
    public boolean isWeekend(LocalDate d) {
        if (d == null) {
            return false;
        }
        DayOfWeek w = d.getDayOfWeek();
        return w == DayOfWeek.SATURDAY || w == DayOfWeek.SUNDAY;
    }
}
