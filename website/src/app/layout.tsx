import type { Metadata, Viewport } from "next";
import { Geist, Geist_Mono } from "next/font/google";
import { Providers } from "@/components/Providers";
import { SkipLink } from "@/components/SkipLink";
import "./globals.css";

const geistSans = Geist({
  subsets: ["latin"],
  weight: ["400", "500", "600"],
  variable: "--font-geist",
});

const geistMono = Geist_Mono({
  subsets: ["latin"],
  weight: ["400", "500", "600"],
  variable: "--font-geist-mono",
});

const resolvedHost =
  process.env.VERCEL_PROJECT_PRODUCTION_URL ||
  process.env.VERCEL_URL ||
  "lumyn-web.vercel.app";
const siteUrl = `https://${resolvedHost}`;

export const metadata: Metadata = {
  metadataBase: new URL(siteUrl),
  title: {
    default: "Lumyn: Offline Hi-Res Android Music Player",
    template: "%s | Lumyn",
  },
  description:
    "An offline Android player for FLAC, WAV, ALAC, and MP3 libraries with a 32-bit float DSP chain, USB DAC bit-perfect output, and zero network permissions.",
  alternates: {
    canonical: "/",
  },
  icons: {
    icon: [
      { url: "/favicon.svg", type: "image/svg+xml" },
      { url: "/icon.svg", type: "image/svg+xml" },
    ],
  },
  openGraph: {
    type: "website",
    url: "/",
    siteName: "Lumyn",
    title: "Lumyn: Offline Hi-Res Android Music Player",
    description:
      "32-bit float DSP equalizer, bit-perfect USB DAC routing, in-file tag editing, and zero network permissions for local Android music libraries.",
  },
  twitter: {
    card: "summary_large_image",
    title: "Lumyn: Offline Hi-Res Android Music Player",
    description:
      "Offline Hi-Res Android music player with 32-bit float DSP and zero network permissions.",
  },
  robots: {
    index: true,
    follow: true,
  },
};

export const viewport: Viewport = {
  viewportFit: "cover",
  themeColor: [
    { media: "(prefers-color-scheme: dark)", color: "#0b1417" },
    { media: "(prefers-color-scheme: light)", color: "#f5fafc" },
  ],
};

const themeInitScript = `
(function() {
  try {
    var t = localStorage.getItem('lumyn-web-theme');
    document.documentElement.setAttribute('data-theme', (t === 'light' || t === 'dark') ? t : 'dark');
    var l = localStorage.getItem('lumyn-web-locale');
    if (l === 'id' || l === 'en') {
      document.documentElement.lang = l;
    }
  } catch (e) {
    document.documentElement.setAttribute('data-theme', 'dark');
  }
})();
`;

export default function RootLayout({
  children,
}: Readonly<{
  children: React.ReactNode;
}>) {
  return (
    <html
      lang="en"
      data-theme="dark"
      suppressHydrationWarning
      className={`${geistSans.variable} ${geistMono.variable}`}
    >
      <head>
        <script dangerouslySetInnerHTML={{ __html: themeInitScript }} />
      </head>
      <body className="antialiased">
        <Providers>
          <SkipLink />
          {children}
        </Providers>
      </body>
    </html>
  );
}
