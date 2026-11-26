"use client";

import React, { useState } from "react";
import { Check } from "@phosphor-icons/react";
import { useAppPreferences } from "@/components/Providers";

export function CapabilitiesShowcase() {
  const { copy } = useAppPreferences();
  const [activeScreenIndex, setActiveScreenIndex] = useState(0);

  const screens = copy.capabilities.screens;
  const activeScreen = screens[activeScreenIndex] ?? screens[0];

  return (
    <section
      id="capabilities"
      className="px-4 py-16 sm:px-6 md:py-24"
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

        <div className="mt-10 grid items-start gap-10 lg:grid-cols-12 lg:gap-12">
          <div className="flex flex-col items-center lg:col-span-5">
            <div
              role="tablist"
              aria-label="App screen selector"
              className="mb-6 flex w-full flex-wrap justify-center gap-2 lg:justify-start"
            >
              {screens.map((screen, idx) => {
                const selected = idx === activeScreenIndex;
                return (
                  <button
                    key={screen.id}
                    type="button"
                    role="tab"
                    aria-selected={selected}
                    onClick={() => setActiveScreenIndex(idx)}
                    className="button-sm min-h-11 rounded-xl px-3.5 py-2 transition-colors duration-200"
                    style={{
                      backgroundColor: selected
                        ? "var(--accent-fill)"
                        : "var(--surface)",
                      color: selected ? "var(--on-accent)" : "var(--text-secondary)",
                      border: selected
                        ? "1px solid var(--accent-fill)"
                        : "1px solid var(--hairline)",
                    }}
                  >
                    {screen.label}
                  </button>
                );
              })}
            </div>

            <div
              className="w-full max-w-[290px] rounded-3xl p-2"
              style={{
                backgroundColor: "var(--surface)",
                border: "1px solid var(--hairline-strong)",
                boxShadow: "var(--shadow-device)",
              }}
            >
              <img
                src={activeScreen.src}
                alt={activeScreen.alt}
                width={activeScreen.width}
                height={activeScreen.height}
                loading="lazy"
                className="block h-auto w-full rounded-2xl"
              />
            </div>
          </div>

          <div className="lg:col-span-7">
            <div className="lumyn-card rounded-2xl p-6">
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
                    className="body-5 inline-flex items-center gap-1.5 rounded-lg px-3 py-1.5 text-[var(--text-primary)] lumyn-raised"
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
              <p className="body-5 text-[var(--text-muted)]">
                {copy.capabilities.pillarsHeading}
              </p>
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
