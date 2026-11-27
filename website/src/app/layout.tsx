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
  "lunarias.vercel.app";
const siteUrl = `https://${resolvedHost}`;
const isPreview = process.env.VERCEL_ENV === "preview";

export const metadata: Metadata = {
  metadataBase: new URL(siteUrl),
  title: {
    default: "Lunaria: Offline Hi-Res Android & Windows Music Player",
    template: "%s | Lunaria",
  },
  description:
    "An offline Android and Windows player for FLAC, WAV, ALAC, and MP3 libraries with a 32-bit float DSP chain, USB DAC bit-perfect output, and zero network permissions.",
  alternates: {
    canonical: "/",
  },
  icons: {
    icon: [{ url: "/favicon.svg", type: "image/svg+xml" }],
  },
  openGraph: {
    type: "website",
    locale: "en_US",
    url: "/",
    siteName: "Lunaria",
    title: "Lunaria: Offline Hi-Res Android & Windows Music Player",
    description:
      "32-bit float DSP equalizer, bit-perfect USB DAC routing, in-file tag editing, and zero network permissions for local music libraries.",
  },
  twitter: {
    card: "summary_large_image",
    title: "Lunaria: Offline Hi-Res Android & Windows Music Player",
    description:
      "Offline Hi-Res music player with 32-bit float DSP and zero network permissions.",
  },
  robots: isPreview
    ? { index: false, follow: false }
    : { index: true, follow: true },
};

export const viewport: Viewport = {
  viewportFit: "cover",
  themeColor: [
    { media: "(prefers-color-scheme: dark)", color: "#0d101b" },
    { media: "(prefers-color-scheme: light)", color: "#f5f7fc" },
  ],
};

const themeInitScript = `
(function() {
  try {
    var t = localStorage.getItem('lunaria-web-theme');
    document.documentElement.setAttribute('data-theme', (t === 'light' || t === 'dark') ? t : 'dark');
    var l = localStorage.getItem('lunaria-web-locale');
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
