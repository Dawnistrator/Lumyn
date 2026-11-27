"use client";

import React, { useEffect, useMemo, useRef, useState } from "react";
import { useAppPreferences } from "@/components/Providers";

export function TaglineReveal() {
  const { copy } = useAppPreferences();
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [revealedCount, setRevealedCount] = useState(0);
  const [reducedMotion, setReducedMotion] = useState(false);

  const lines = copy.tagline.lines;
  const wordCounts = useMemo(() => {
    const perLine = lines.map((line) => line.trim().split(/\s+/));
    const total = perLine.reduce((sum, words) => sum + words.length, 0);
    return { perLine, total };
  }, [lines]);

  useEffect(() => {
    const mediaQuery = window.matchMedia("(prefers-reduced-motion: reduce)");
    const syncMotion = (matches: boolean) => {
      setReducedMotion(matches);
      if (matches) {
        setRevealedCount(wordCounts.total);
      }
    };
    syncMotion(mediaQuery.matches);

    const handleMediaChange = (event: MediaQueryListEvent) => {
      syncMotion(event.matches);
    };
    mediaQuery.addEventListener("change", handleMediaChange);

    if (mediaQuery.matches) {
      return () => mediaQuery.removeEventListener("change", handleMediaChange);
    }

    let isVisible = false;
    let completed = false;
    let listenersAttached = false;
    let rafId = 0;

    const detachScrollListeners = () => {
      if (!listenersAttached) return;
      listenersAttached = false;
      window.removeEventListener("scroll", scheduleUpdate);
      window.removeEventListener("resize", scheduleUpdate);
    };

    const updateProgress = () => {
      rafId = 0;
      const el = containerRef.current;
      if (!el || completed) return;
      const rect = el.getBoundingClientRect();
      const vh = window.innerHeight || 800;
      const start = vh * 0.85;
      const end = vh * 0.3;
      const ratio = Math.min(1, Math.max(0, (start - rect.top) / Math.max(1, start - end)));
      const targetCount = Math.min(
        wordCounts.total,
        Math.max(0, Math.ceil(ratio * wordCounts.total)),
      );
      setRevealedCount((prev) => (targetCount > prev ? targetCount : prev));
      if (targetCount >= wordCounts.total) {
        completed = true;
        detachScrollListeners();
        observer.disconnect();
      }
    };

    function scheduleUpdate() {
      if (!isVisible || completed || rafId !== 0) return;
      rafId = window.requestAnimationFrame(updateProgress);
    }

    const attachScrollListeners = () => {
      if (listenersAttached || completed) return;
      listenersAttached = true;
      window.addEventListener("scroll", scheduleUpdate, { passive: true });
      window.addEventListener("resize", scheduleUpdate, { passive: true });
    };

    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          isVisible = entry.isIntersecting;
          if (isVisible) {
            attachScrollListeners();
            scheduleUpdate();
          } else {
            detachScrollListeners();
          }
        }
      },
      { threshold: [0, 0.25, 0.5, 0.75, 1] },
    );

    if (containerRef.current) {
      observer.observe(containerRef.current);
    }

    return () => {
      mediaQuery.removeEventListener("change", handleMediaChange);
      observer.disconnect();
      detachScrollListeners();
      if (rafId !== 0) window.cancelAnimationFrame(rafId);
    };
  }, [wordCounts.total]);

  let runningWordIndex = 0;

  return (
    <section
      className="px-4 py-14 sm:px-6 md:py-24 lg:py-28"
      style={{ borderTop: "1px solid var(--hairline)" }}
    >
      <div ref={containerRef} className="mx-auto max-w-6xl">
        <div className="mx-auto max-w-[680px] text-center">
          <h2 className="heading-2 text-[var(--text-primary)]">
            {wordCounts.perLine.map((words, lineIdx) => (
              <span key={lineIdx} className="block">
                {words.map((word) => {
                  const index = runningWordIndex++;
                  const revealed = reducedMotion || index < revealedCount;
                  return (
                    <span
                      key={`${lineIdx}-${index}`}
                      className="mr-[0.25em] inline-block transition-opacity duration-500 last:mr-0"
                      style={{
                        opacity: revealed ? 1 : 0.28,
                        transitionTimingFunction: "cubic-bezier(0.22, 1, 0.36, 1)",
                      }}
                    >
                      {word}
                    </span>
                  );
                })}
              </span>
            ))}
          </h2>
        </div>
      </div>
    </section>
  );
}
