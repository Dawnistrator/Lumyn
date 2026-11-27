"use client";

import React, { useRef, useState } from "react";
import Image from "next/image";
import { Check } from "@phosphor-icons/react";
import { useAppPreferences } from "@/components/Providers";

export function CapabilitiesShowcase() {
  const { copy } = useAppPreferences();
  const [activeScreenIndex, setActiveScreenIndex] = useState(0);
  const tabRefs = useRef<Array<HTMLButtonElement | null>>([]);

  const screens = copy.capabilities.screens;
  const activeScreen = screens[activeScreenIndex] ?? screens[0];

  const handleTabKeyDown = (event: React.KeyboardEvent<HTMLButtonElement>, idx: number) => {
    let nextIdx: number | null = null;
    if (event.key === "ArrowRight") {
      nextIdx = (idx + 1) % screens.length;
    } else if (event.key === "ArrowLeft") {
      nextIdx = (idx - 1 + screens.length) % screens.length;
    } else if (event.key === "Home") {
      nextIdx = 0;
    } else if (event.key === "End") {
      nextIdx = screens.length - 1;
    }
    if (nextIdx === null) return;
    event.preventDefault();
    setActiveScreenIndex(nextIdx);
    tabRefs.current[nextIdx]?.focus();
  };

  return (
    <section
      id="capabilities"
      className="px-4 py-12 sm:px-6 md:py-20 lg:py-24"
      style={{ borderTop: "1px solid var(--hairline)" }}
    >
      <div className="mx-auto max-w-6xl">
        <div className="max-w-[680px]">
          <p className="body-5 text-[var(--accent)]">{copy.capabilities.sectionLabel}</p>
          <h2 className="heading-3 mt-2 text-[var(--text-primary)]">
            {copy.capabilities.heading}
          </h2>
          <p className="body-3 mt-4 text-[var(--text-secondary)]">
            {copy.capabilities.lead}
          </p>
        </div>

        <div className="mt-8 grid items-start gap-8 md:mt-10 md:grid-cols-12 md:gap-8 lg:gap-12">
          <div className="flex min-w-0 flex-col items-center md:col-span-5">
            <div
              role="tablist"
              aria-label={copy.capabilities.screenSelectorLabel}
              className="mb-5 grid w-full grid-cols-2 gap-2 sm:mb-6 sm:flex sm:flex-wrap sm:justify-center md:justify-start"
            >
              {screens.map((screen, idx) => {
                const selected = idx === activeScreenIndex;
                return (
                  <button
                    key={screen.id}
                    ref={(el) => {
                      tabRefs.current[idx] = el;
                    }}
                    id={`cap-tab-${screen.id}`}
                    type="button"
                    role="tab"
                    aria-selected={selected}
                    aria-controls={`cap-panel-${screen.id}`}
                    tabIndex={selected ? 0 : -1}
                    onClick={() => setActiveScreenIndex(idx)}
                    onKeyDown={(e) => handleTabKeyDown(e, idx)}
                    className="button-sm min-h-11 rounded-xl px-3 py-2 text-center transition-colors duration-200"
                    style={{
                      backgroundColor: selected
                        ? "var(--accent-fill)"
                        : "var(--surface)",
                      color: selected ? "var(--on-accent)" : "var(--text-secondary)",
                      border: selected
                        ? "1px solid var(--accent-fill)"
                        : "1px solid var(--ui-border)",
                    }}
                  >
                    {screen.label}
                  </button>
                );
              })}
            </div>

            <div
              className="w-full max-w-[240px] rounded-3xl p-2 sm:max-w-[260px] lg:max-w-[290px]"
              style={{
                backgroundColor: "var(--surface)",
                border: "1px solid var(--hairline-strong)",
                boxShadow: "var(--shadow-device)",
              }}
            >
              <Image
                src={activeScreen.src}
                alt={activeScreen.alt}
                width={activeScreen.width}
                height={activeScreen.height}
                sizes="(max-width: 639px) 240px, (max-width: 1023px) 260px, 290px"
                className="block aspect-[720/1507] h-auto w-full rounded-2xl"
              />
            </div>
          </div>

          <div className="min-w-0 md:col-span-7">
            <div
              id={`cap-panel-${activeScreen.id}`}
              role="tabpanel"
              aria-labelledby={`cap-tab-${activeScreen.id}`}
              className="lunaria-card rounded-2xl p-4 sm:p-6"
            >
              <h3 className="heading-5 text-[var(--text-primary)]">
                {activeScreen.title}
              </h3>
              <p className="body-3 mt-3 text-[var(--text-secondary)]">
                {activeScreen.description}
              </p>

              <ul className="mt-5 flex flex-wrap gap-2">
                {activeScreen.specs.map((spec) => (
                  <li
                    key={spec}
                    className="body-5 inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-[var(--text-primary)] lunaria-raised"
                  >
                    <Check
                      size={15}
                      aria-hidden="true"
                      className="text-[var(--accent)]"
                    />
                    <span>{spec}</span>
                  </li>
                ))}
              </ul>
            </div>

            <div className="mt-8">
              <h3 className="body-5 text-[var(--text-muted)]">
                {copy.capabilities.pillarsHeading}
              </h3>
              <div
                className="mt-3 divide-y"
                style={{ borderColor: "var(--hairline)" }}
              >
                {copy.capabilities.pillars.map((pillar) => (
                  <div
                    key={pillar.title}
                    className="py-4"
                    style={{ borderColor: "var(--hairline)" }}
                  >
                    <h4 className="title-3 text-[var(--text-primary)]">
                      {pillar.title}
                    </h4>
                    <p className="body-6 mt-1.5 text-[var(--text-secondary)]">
                      {pillar.detail}
                    </p>
                  </div>
                ))}
              </div>
            </div>
          </div>
        </div>
      </div>
    </section>
  );
}
