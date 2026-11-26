"use client";

import React, { useEffect, useMemo, useRef, useState } from "react";
import { useAppPreferences } from "@/components/Providers";

export function TaglineReveal() {
  const { copy } = useAppPreferences();
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [progress, setProgress] = useState(0);
  const [reducedMotion, setReducedMotion] = useState(false);

  const lines = copy.tagline.lines;
  const wordCounts = useMemo(() => {
    const perLine = lines.map((line) => line.trim().split(/\s+/));
    const total = perLine.reduce((sum, words) => sum + words.length, 0);
    return { perLine, total };
  }, [lines]);

  useEffect(() => {
    const mediaQuery = window.matchMedia("(prefers-reduced-motion: reduce)");
    if (mediaQuery.matches) {
      setReducedMotion(true);
      setProgress(1);
      return;
    }

    let isVisible = false;
    let rafId = 0;

    const updateProgress = () => {
      rafId = 0;
      const el = containerRef.current;
      if (!el) return;
      const rect = el.getBoundingClientRect();
      const vh = window.innerHeight || 800;
      const start = vh * 0.85;
      const end = vh * 0.3;
      const ratio = (start - rect.top) / Math.max(1, start - end);
      setProgress(Math.min(1, Math.max(0, ratio)));
    };

    const scheduleUpdate = () => {
      if (!isVisible || rafId !== 0) return;
      rafId = window.requestAnimationFrame(updateProgress);
    };

    const observer = new IntersectionObserver(
      (entries) => {
        for (const entry of entries) {
          isVisible = entry.isIntersecting;
          if (isVisible) scheduleUpdate();
        }
      },
      { threshold: [0, 0.25, 0.5, 0.75, 1] },
    );

    if (containerRef.current) {
      observer.observe(containerRef.current);
    }

    window.addEventListener("scroll", scheduleUpdate, { passive: true });
    window.addEventListener("resize", scheduleUpdate, { passive: true });
    updateProgress();

    return () => {
      observer.disconnect();
      window.removeEventListener("scroll", scheduleUpdate);
      window.removeEventListener("resize", scheduleUpdate);
      if (rafId !== 0) window.cancelAnimationFrame(rafId);
    };
  }, []);

  let runningWordIndex = 0;

  return (
    <section
      className="px-4 py-20 sm:px-6 md:py-28"
      style={{ borderTop: "1px solid var(--hairline)" }}
    >
      <div ref={containerRef} className="mx-auto max-w-6xl">
        <div className="mx-auto max-w-[680px] text-center">
          <h2 className="heading-2 text-[var(--text-primary)]">
            {wordCounts.perLine.map((words, lineIdx) => (
              <span key={lineIdx} className="block">
                {words.map((word) => {
                  const index = runningWordIndex++;
                  const threshold =
                    wordCounts.total > 1 ? index / (wordCounts.total - 1) : 0;
                  const revealed = reducedMotion || progress >= threshold * 0.92;
                  return (
                    <span
                      key={`${lineIdx}-${index}`}
                      className="inline-block transition-opacity duration-500"
                      style={{
                        opacity: revealed ? 1 : 0.28,
                        transitionTimingFunction: "cubic-bezier(0.22, 1, 0.36, 1)",
                      }}
                    >
                      {word}
                      {"\u00A0"}
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
