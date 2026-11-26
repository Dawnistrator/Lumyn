import React from "react";

interface LumynMarkProps {
  className?: string;
}

/** Five curved audio-level bars derived directly from Lumyn ic_launcher_foreground.xml. */
export function LumynMark({ className = "h-7 w-7" }: LumynMarkProps) {
  return (
    <svg
      xmlns="http://www.w3.org/2000/svg"
      viewBox="0 0 108 108"
      className={className}
      aria-hidden="true"
    >
      <rect width="108" height="108" rx="24" fill="#0E6178" />
      <path
        fill="#F5FAFC"
        d="M30,48h6v12h-6zM40,40h6v28h-6zM51,32h6v44h-6zM62,40h6v28h-6zM72,48h6v12h-6z"
      />
    </svg>
  );
}
