"use client";

import React from "react";
import { useAppPreferences } from "@/components/Providers";

export function SkipLink() {
  const { copy } = useAppPreferences();
  return (
    <a
      href="#main-content"
      className="btn-primary button-sm sr-only fixed top-4 left-4 z-50 rounded-xl px-4 py-2 no-underline focus:not-sr-only"
    >
      {copy.skipToContent}
    </a>
  );
}
