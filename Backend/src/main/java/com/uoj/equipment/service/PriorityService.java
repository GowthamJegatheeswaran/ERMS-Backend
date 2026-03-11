package com.uoj.equipment.service;

import com.uoj.equipment.enums.PurposeType;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

/**
 * Priority score system for equipment requests.
 *
 * Score range: 0 – 100
 *
 * Three weighted components:
 *
 * 1. PURPOSE WEIGHT  (max 50 pts)
 *    LABS      → 50   Scheduled lab sessions are timetable-locked — highest urgency
 *    LECTURE   → 45   Classroom lectures are time-critical
 *    PROJECT   → 35   Final-year / research projects need reliable equipment
 *    RESEARCH  → 30   Research timelines are important but more flexible
 *    PERSONAL  → 10   Personal use has lowest institutional priority
 *
 * 2. URGENCY WEIGHT  (max 30 pts)
 *    Based on how soon the fromDate is from today:
 *    Same day / overdue  → 30
 *    1 day ahead         → 25
 *    2 days ahead        → 20
 *    3–4 days ahead      → 15
 *    5–7 days ahead      → 10
 *    8–14 days ahead     →  5
 *    15+ days ahead      →  0
 *
 * 3. DURATION WEIGHT  (max 20 pts)
 *    Shorter duration = higher priority (quick turnaround):
 *    1 day               → 20
 *    2 days              → 15
 *    3–4 days            → 10
 *    5–7 days            →  5
 *    8+ days             →  0
 *
 * Final score is clamped to [0, 100].
 * Higher score = higher priority = appears at TOP of TO's approval queue.
 */
@Service
public class PriorityService {

    public int calculate(PurposeType purpose,
                         LocalDate fromDate,
                         LocalDate toDate,
                         boolean specialFlag) {

        // ── 1. Purpose weight ──────────────────────────────────────────────
        int purposePts = switch (purpose) {
            case LABS     -> 50;
            case LECTURE  -> 45;
            case PROJECT  -> 35;
            case RESEARCH -> 30;
            case PERSONAL -> 10;
        };

        // ── 2. Urgency weight ──────────────────────────────────────────────
        long daysUntil = ChronoUnit.DAYS.between(LocalDate.now(), fromDate);
        int urgencyPts;
        if (daysUntil <= 0) {
            urgencyPts = 30;   // same day or overdue — issue immediately
        } else if (daysUntil == 1) {
            urgencyPts = 25;
        } else if (daysUntil == 2) {
            urgencyPts = 20;
        } else if (daysUntil <= 4) {
            urgencyPts = 15;
        } else if (daysUntil <= 7) {
            urgencyPts = 10;
        } else if (daysUntil <= 14) {
            urgencyPts = 5;
        } else {
            urgencyPts = 0;
        }

        // ── 3. Duration weight ─────────────────────────────────────────────
        long durationDays = (fromDate != null && toDate != null)
                ? ChronoUnit.DAYS.between(fromDate, toDate) + 1
                : 1;
        int durationPts;
        if (durationDays <= 1) {
            durationPts = 20;
        } else if (durationDays == 2) {
            durationPts = 15;
        } else if (durationDays <= 4) {
            durationPts = 10;
        } else if (durationDays <= 7) {
            durationPts = 5;
        } else {
            durationPts = 0;
        }

        // ── Special override ───────────────────────────────────────────────
        // e.g., HOD/admin-flagged urgent requests get +10
        int specialPts = specialFlag ? 10 : 0;

        int total = purposePts + urgencyPts + durationPts + specialPts;
        return Math.max(0, Math.min(100, total));
    }
}