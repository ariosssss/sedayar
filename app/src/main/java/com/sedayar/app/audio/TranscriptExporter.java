package com.sedayar.app.audio;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;

/**
 * Builds the downloadable transcript files (TXT / HTML / SRT / Markdown)
 * and hands them to the system share sheet via FileProvider.
 */
public final class TranscriptExporter {

    public interface ExportFormat {
        String key();

        String extension();

        String mime();

        String build(String title, Transcript transcript, String summary);
    }

    private TranscriptExporter() {
    }

    public static final ExportFormat TXT = new ExportFormat() {
        @Override
        public String key() {
            return "txt";
        }

        @Override
        public String extension() {
            return "txt";
        }

        @Override
        public String mime() {
            return "text/plain";
        }

        @Override
        public String build(String title, Transcript t, String summary) {
            StringBuilder sb = new StringBuilder();
            sb.append(title).append("\n\n");
            if (summary != null && !summary.trim().isEmpty()) {
                sb.append("— خلاصه —\n").append(summary.trim()).append("\n\n");
            }
            sb.append("— متن پیاده‌شده —\n");
            for (Transcript.Segment s : t.segments) {
                sb.append("[").append(Transcript.shortTime(s.startMs)).append("] ")
                        .append(s.text).append("\n");
            }
            return sb.toString();
        }
    };

    public static final ExportFormat SRT = new ExportFormat() {
        @Override
        public String key() {
            return "srt";
        }

        @Override
        public String extension() {
            return "srt";
        }

        @Override
        public String mime() {
            return "application/x-subrip";
        }

        @Override
        public String build(String title, Transcript t, String summary) {
            StringBuilder sb = new StringBuilder();
            int n = 1;
            for (Transcript.Segment s : t.segments) {
                sb.append(n++).append("\n");
                sb.append(Transcript.timecode(s.startMs, true)).append(" --> ")
                        .append(Transcript.timecode(Math.max(s.endMs, s.startMs + 800), true))
                        .append("\n");
                sb.append(s.text).append("\n\n");
            }
            return sb.toString();
        }
    };

    public static final ExportFormat MD = new ExportFormat() {
        @Override
        public String key() {
            return "md";
        }

        @Override
        public String extension() {
            return "md";
        }

        @Override
        public String mime() {
            return "text/markdown";
        }

        @Override
        public String build(String title, Transcript t, String summary) {
            StringBuilder sb = new StringBuilder();
            sb.append("# ").append(title).append("\n\n");
            if (summary != null && !summary.trim().isEmpty()) {
                sb.append("## خلاصه\n\n").append(summary.trim()).append("\n\n");
            }
            sb.append("## متن پیاده‌شده\n\n");
            for (Transcript.Segment s : t.segments) {
                sb.append("**`").append(Transcript.shortTime(s.startMs))
                        .append("`** ").append(s.text).append("\n\n");
            }
            return sb.toString();
        }
    };

    public static final ExportFormat HTML = new ExportFormat() {
        @Override
        public String key() {
            return "html";
        }

        @Override
        public String extension() {
            return "html";
        }

        @Override
        public String mime() {
            return "text/html";
        }

        @Override
        public String build(String title, Transcript t, String summary) {
            StringBuilder sb = new StringBuilder();
            sb.append("<!DOCTYPE html>\n<html lang=\"fa\" dir=\"rtl\">\n<head>\n");
            sb.append("<meta charset=\"utf-8\">\n<title>").append(esc(title)).append("</title>\n");
            sb.append("<style>\n")
                    .append("body{font-family:Vazirmatn,Tahoma,sans-serif;background:#f5f7fa;")
                    .append("color:#1a2233;margin:0;padding:32px 16px;line-height:2}\n")
                    .append(".page{max-width:760px;margin:0 auto;background:#fff;border-radius:16px;")
                    .append("padding:32px;box-shadow:0 2px 12px rgba(20,40,90,.08)}\n")
                    .append("h1{font-size:24px;margin:0 0 6px}\n")
                    .append(".meta{color:#667085;font-size:13px;margin-bottom:24px}\n")
                    .append(".summary{background:#eef2ff;border-radius:12px;padding:16px 20px;")
                    .append("margin-bottom:24px}\n")
                    .append(".summary h2{font-size:16px;margin:0 0 8px;color:#3b4ed8}\n")
                    .append(".seg{display:flex;gap:14px;padding:8px 0;border-bottom:1px dashed #e3e8f0}\n")
                    .append(".t{color:#3b6fe0;font-size:12px;min-width:56px;padding-top:4px;")
                    .append("font-variant-numeric:tabular-nums;direction:ltr}\n")
                    .append(".x{flex:1}\n")
                    .append("@media print{body{background:#fff;padding:0}")
                    .append(".page{box-shadow:none}}\n")
                    .append("</style>\n</head>\n<body>\n<div class=\"page\">\n");
            sb.append("<h1>").append(esc(title)).append("</h1>\n");
            sb.append("<div class=\"meta\">صدایار — پیاده‌سازی گفتار</div>\n");
            if (summary != null && !summary.trim().isEmpty()) {
                sb.append("<div class=\"summary\"><h2>خلاصه</h2><p>")
                        .append(esc(summary.trim()).replace("\n", "<br>"))
                        .append("</p></div>\n");
            }
            for (Transcript.Segment s : t.segments) {
                sb.append("<div class=\"seg\"><span class=\"t\">")
                        .append(Transcript.shortTime(s.startMs))
                        .append("</span><span class=\"x\">").append(esc(s.text))
                        .append("</span></div>\n");
            }
            sb.append("</div>\n</body>\n</html>\n");
            return sb.toString();
        }

        private String esc(String s) {
            if (s == null) {
                return "";
            }
            return s.replace("&", "&amp;").replace("<", "&lt;")
                    .replace(">", "&gt;").replace("\"", "&quot;");
        }
    };

    /** Builds the file in cache/exports and opens the share sheet. */
    public static void share(Context context, ExportFormat format, String baseName,
                             String title, Transcript transcript, String summary) {
        String content = format.build(title, transcript, summary);
        File dir = new File(context.getCacheDir(), "exports");
        if (!dir.exists()) {
            dir.mkdirs();
        }
        String safe = baseName.replaceAll("[^\\p{L}\\p{Nd}._-]+", "_");
        File out = new File(dir, safe + "." + format.extension());
        try (OutputStreamWriter w = new OutputStreamWriter(
                new FileOutputStream(out), StandardCharsets.UTF_8)) {
            w.write(content);
        } catch (Exception ignored) {
            return;
        }

        Uri uri = FileProvider.getUriForFile(context,
                context.getPackageName() + ".files", out);
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType(format.mime());
        send.putExtra(Intent.EXTRA_STREAM, uri);
        send.putExtra(Intent.EXTRA_SUBJECT, title);
        send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(Intent.createChooser(send, null));
    }
}
