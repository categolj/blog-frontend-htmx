"use strict";
{
  // FFmpeg.wasm is loaded on-demand from CDN. Single-threaded core @ffmpeg/core
  // (not @ffmpeg/core-mt) does not need SharedArrayBuffer, so no COOP/COEP
  // headers are required on the response.
  const FFMPEG_VERSION = "0.12.15";
  const UTIL_VERSION = "0.12.2";
  const CORE_VERSION = "0.12.10";

  const FFMPEG_MODULE_URL = `https://esm.sh/@ffmpeg/ffmpeg@${FFMPEG_VERSION}`;
  const UTIL_MODULE_URL = `https://esm.sh/@ffmpeg/util@${UTIL_VERSION}`;
  const CORE_BASE_URL = `https://unpkg.com/@ffmpeg/core@${CORE_VERSION}/dist/esm`;
  // The FFmpeg class instantiates a Worker from an internal URL baked into its
  // module. Cross-origin module Worker construction is blocked, so we fetch the
  // self-contained esm.sh bundle and hand FFmpeg a same-origin blob URL for it.
  const FFMPEG_WORKER_URL = `https://esm.sh/@ffmpeg/ffmpeg@${FFMPEG_VERSION}/es2022/worker.bundle.mjs`;

  const MAX_LOG_LINES = 100;

  const setStatus = (root, state, label) => {
    const badge = root.querySelector(".lab-status-badge");
    if (!badge) return;
    badge.dataset.status = state;
    badge.textContent = label;
  };

  const toggle = (el, shown) => {
    if (!el) return;
    if (shown) el.removeAttribute("hidden");
    else el.setAttribute("hidden", "");
  };

  const init = async (root) => {
    if (root.dataset.movToGifInit === "1") return;
    root.dataset.movToGifInit = "1";

    const dropzone = root.querySelector("[data-dropzone]");
    const fileInput = root.querySelector("[data-file-input]");
    const emptyView = root.querySelector(".lab-dropzone-empty");
    const selectedView = root.querySelector(".lab-dropzone-selected");
    const fileNameEl = root.querySelector(".lab-file-name");
    const fileSizeEl = root.querySelector(".lab-file-size");
    const convertBtn = root.querySelector("[data-convert]");
    const progressWrap = root.querySelector("[data-progress-wrap]");
    const progressBar = root.querySelector("[data-progress-bar]");
    const resultWrap = root.querySelector("[data-result]");
    const resultImg = root.querySelector("[data-result-img]");
    const downloadBtn = root.querySelector("[data-download]");
    const logWrap = root.querySelector("[data-log-wrap]");
    const logEl = root.querySelector("[data-log]");
    const logCountEl = root.querySelector("[data-log-count]");

    const scaleInput = root.querySelector('input[name="scale"]');
    const fpsInput = root.querySelector('input[name="fps"]');
    const qualitySelect = root.querySelector('select[name="quality"]');
    const speedSelect = root.querySelector('select[name="speed"]');

    let inputFile = null;
    let outputUrl = null;
    let loaded = false;
    let converting = false;
    const logMessages = [];

    const appendLog = (message) => {
      logMessages.push(message);
      if (logMessages.length > MAX_LOG_LINES) {
        logMessages.splice(0, logMessages.length - MAX_LOG_LINES);
      }
      logEl.textContent = logMessages.join("\n");
      logCountEl.textContent = String(logMessages.length);
      toggle(logWrap, logMessages.length > 0);
    };

    const clearLog = () => {
      logMessages.length = 0;
      logEl.textContent = "";
      logCountEl.textContent = "0";
      toggle(logWrap, false);
    };

    const setProgress = (pct) => {
      progressBar.style.width = `${pct}%`;
    };

    const updateConvertLabel = () => {
      convertBtn.disabled = !loaded || !inputFile || converting;
      if (converting) {
        convertBtn.textContent = `Converting… ${Math.round(Number(progressBar.style.width.replace("%", "")) || 0)}%`;
      } else {
        convertBtn.textContent = "Convert to GIF";
      }
    };

    const setInputFile = (file) => {
      inputFile = file;
      if (outputUrl) {
        URL.revokeObjectURL(outputUrl);
        outputUrl = null;
      }
      toggle(resultWrap, false);
      setProgress(0);
      clearLog();
      toggle(emptyView, false);
      toggle(selectedView, true);
      fileNameEl.textContent = file.name;
      fileSizeEl.textContent = `${(file.size / 1024 / 1024).toFixed(1)} MB`;
      updateConvertLabel();
    };

    dropzone.addEventListener("click", (e) => {
      // Clicks on the <input type="file"> itself bubble up here — ignore to
      // avoid re-opening the picker twice.
      if (e.target === fileInput) return;
      fileInput.click();
    });
    dropzone.addEventListener("keydown", (e) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        fileInput.click();
      }
    });
    dropzone.addEventListener("dragover", (e) => {
      e.preventDefault();
      dropzone.classList.add("is-dragover");
    });
    dropzone.addEventListener("dragleave", (e) => {
      e.preventDefault();
      dropzone.classList.remove("is-dragover");
    });
    dropzone.addEventListener("drop", (e) => {
      e.preventDefault();
      dropzone.classList.remove("is-dragover");
      const file = e.dataTransfer?.files?.[0];
      if (file) setInputFile(file);
    });
    fileInput.addEventListener("change", () => {
      const file = fileInput.files?.[0];
      if (file) setInputFile(file);
    });

    let ffmpeg = null;
    let fetchFile = null;
    let toBlobURL = null;

    try {
      const [ffmpegMod, utilMod] = await Promise.all([
        import(FFMPEG_MODULE_URL),
        import(UTIL_MODULE_URL),
      ]);
      fetchFile = utilMod.fetchFile;
      toBlobURL = utilMod.toBlobURL;
      ffmpeg = new ffmpegMod.FFmpeg();

      ffmpeg.on("progress", ({ progress }) => {
        if (progress > 0 && progress < 1) {
          const pct = Math.round(progress * 100);
          setProgress(pct);
          updateConvertLabel();
        }
      });
      ffmpeg.on("log", ({ message }) => appendLog(message));

      // toBlobURL bypasses worker-URL CORS: fetch the JS/WASM, wrap in a blob,
      // and hand FFmpeg same-origin blob: URLs for its internal Worker and
      // WASM module.
      const [classWorkerURL, coreURL, wasmURL] = await Promise.all([
        toBlobURL(FFMPEG_WORKER_URL, "text/javascript"),
        toBlobURL(`${CORE_BASE_URL}/ffmpeg-core.js`, "text/javascript"),
        toBlobURL(`${CORE_BASE_URL}/ffmpeg-core.wasm`, "application/wasm"),
      ]);
      await ffmpeg.load({ classWorkerURL, coreURL, wasmURL });

      loaded = true;
      setStatus(root, "ready", "Ready");
      updateConvertLabel();
    } catch (err) {
      console.error("Failed to load FFmpeg:", err);
      setStatus(root, "error", "Load failed");
      appendLog(`Load error: ${err instanceof Error ? err.message : String(err)}`);
      return;
    }

    const convert = async () => {
      if (!inputFile || !loaded || converting) return;

      converting = true;
      setProgress(0);
      clearLog();
      toggle(progressWrap, true);
      toggle(resultWrap, false);
      updateConvertLabel();

      try {
        await ffmpeg.writeFile("input.mov", await fetchFile(inputFile));

        const scaleVal = parseInt(scaleInput.value, 10) || 720;
        const fpsVal = parseInt(fpsInput.value, 10) || 10;
        const quality = qualitySelect.value;
        const speed = Number(speedSelect.value) || 1;

        const speedFilter = speed !== 1 ? `setpts=${(1 / speed).toFixed(4)}*PTS,` : "";
        const fpsFilter = `fps=${fpsVal},`;
        const scaleFilter = `scale=${scaleVal}:-1:flags=lanczos`;

        if (quality === "high") {
          const paletteVf = `${speedFilter}${fpsFilter}${scaleFilter},palettegen=max_colors=256:stats_mode=diff`;
          await ffmpeg.exec(["-i", "input.mov", "-vf", paletteVf, "palette.png"]);
          const outputVf = `${speedFilter}${fpsFilter}${scaleFilter} [x]; [x][1:v] paletteuse=dither=floyd_steinberg`;
          await ffmpeg.exec([
            "-i", "input.mov",
            "-i", "palette.png",
            "-lavfi", outputVf,
            "output.gif",
          ]);
        } else {
          const ditherOpt = quality === "medium"
            ? ",split[s0][s1];[s0]palettegen=max_colors=128[p];[s1][p]paletteuse=dither=bayer:bayer_scale=3"
            : "";
          const vf = `${speedFilter}${fpsFilter}${scaleFilter}${ditherOpt}`;
          await ffmpeg.exec(["-i", "input.mov", "-vf", vf, "output.gif"]);
        }

        const data = await ffmpeg.readFile("output.gif");
        const blob = new Blob([data], { type: "image/gif" });
        if (outputUrl) URL.revokeObjectURL(outputUrl);
        outputUrl = URL.createObjectURL(blob);
        resultImg.src = outputUrl;
        toggle(resultWrap, true);
      } catch (err) {
        console.error("Conversion failed:", err);
        appendLog(`Error: ${err instanceof Error ? err.message : "Conversion failed"}`);
      } finally {
        converting = false;
        toggle(progressWrap, false);
        updateConvertLabel();
      }
    };

    convertBtn.addEventListener("click", convert);

    downloadBtn.addEventListener("click", () => {
      if (!outputUrl) return;
      const a = document.createElement("a");
      a.href = outputUrl;
      const baseName = inputFile?.name.replace(/\.[^.]+$/, "") ?? "output";
      a.download = `${baseName}.gif`;
      a.click();
    });
  };

  const boot = () => {
    const root = document.getElementById("mov-to-gif");
    if (root) init(root);
  };

  if (document.readyState === "loading") {
    document.addEventListener("DOMContentLoaded", boot);
  } else {
    boot();
  }
}
