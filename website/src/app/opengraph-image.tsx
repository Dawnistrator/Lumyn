import { ImageResponse } from "next/og";

export const alt = "Lunaria: Offline Hi-Res Android & Windows Music Player";
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
          backgroundColor: "#0D101B",
          padding: "64px",
          color: "#F4F6FC",
          fontFamily: "sans-serif",
        }}
      >
        <div style={{ display: "flex", alignItems: "center", gap: "20px" }}>
          <svg width="64" height="64" viewBox="0 0 108 108">
            <rect width="108" height="108" rx="24" fill="#32428A" />
            <path
              fill="#F4F6FC"
              d="M 27.43 23 H 33.33 A 4.43 4.43 0 0 1 37.76 27.43 V 64.33 A 5.9 5.9 0 0 0 43.67 70.24 H 80.57 A 4.43 4.43 0 0 1 85 74.67 V 80.57 A 4.43 4.43 0 0 1 80.57 85 H 27.43 A 4.43 4.43 0 0 1 23 80.57 V 27.43 A 4.43 4.43 0 0 1 27.43 23 Z M 82.27 46.44 A 17.71 17.71 0 1 1 67.14 24.6 A 13.29 13.29 0 1 0 82.27 46.44 Z M 70.24 39.98 A 4.06 4.06 0 1 1 78.36 39.98 A 4.06 4.06 0 1 1 70.24 39.98 Z"
            />
          </svg>
          <span style={{ fontSize: "34px", fontWeight: 600 }}>Lunaria</span>
        </div>

        <div style={{ display: "flex", flexDirection: "column", gap: "20px" }}>
          <div style={{ fontSize: "62px", fontWeight: 600, lineHeight: 1.08 }}>
            Your local music, played bit for bit.
          </div>
          <div style={{ fontSize: "28px", color: "#B8C0D9", lineHeight: 1.35 }}>
            32-bit float DSP, bit-perfect USB DAC output, and zero network permissions for Android &amp; Windows.
          </div>
        </div>

        <div style={{ display: "flex", fontSize: "22px", color: "#9AB0FF" }}>
          Android 8.0+ · Windows 10/11 · FLAC · WAV · ALAC · MP3 · GPL-3.0
        </div>
      </div>
    ),
    { ...size },
  );
}
