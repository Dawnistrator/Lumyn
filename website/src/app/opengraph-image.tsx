import { ImageResponse } from "next/og";

export const alt = "Lumyn: Offline Hi-Res Android Music Player";
export const size = { width: 1200, height: 630 };
export const contentType = "image/png";

export default function OpenGraphImage() {
  return new ImageResponse(
    (
      <div
        style={{
          height: "100%",
          width: "100%",
          display: "flex",
          flexDirection: "column",
          justifyContent: "space-between",
          backgroundColor: "#0B1417",
          padding: "64px",
          color: "#F5FAFC",
          fontFamily: "sans-serif",
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: "20px" }}>
          <svg width="64" height="64" viewBox="0 0 108 108">
            <rect width="108" height="108" rx="24" fill="#0E6178" />
            <path
              fill="#F5FAFC"
              d="M30,48h6v12h-6zM40,40h6v28h-6zM51,32h6v44h-6zM62,40h6v28h-6zM72,48h6v12h-6z"
            />
          </svg>
          <span style={{ fontSize: "34px", fontWeight: 600 }}>Lumyn</span>
        </div>

        <div style={{ display: "flex", flexDirection: "column", gap: "20px" }}>
          <div style={{ fontSize: "62px", fontWeight: 600, lineHeight: 1.08 }}>
            Your local music, played bit for bit.
          </div>
          <div style={{ fontSize: "28px", color: "#B3C7CE", lineHeight: 1.35 }}>
            32-bit float DSP, bit-perfect USB DAC output, and zero network permissions for Android.
          </div>
        </div>

        <div style={{ display: "flex", fontSize: "22px", color: "#67C6DF" }}>
          Android 8.0+ · FLAC · WAV · ALAC · MP3 · Open Source
        </div>
      </div>
    ),
    { ...size },
  );
}
