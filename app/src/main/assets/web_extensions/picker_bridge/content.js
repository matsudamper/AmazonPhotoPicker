(function () {
  "use strict";

  const NATIVE_APP = "amazonPhotoPicker";
  const BLOB_CHUNK_SIZE = 512 * 1024;
  const pageWin = window.wrappedJSObject;

  // GeckoView の UVPAA 判定が実際の Credential Manager 対応状況と一致せず、
  // Passkey の選択肢が表示されないサイトがあるため利用可能として扱う。
  if (typeof pageWin.PublicKeyCredential === "function") {
    pageWin.PublicKeyCredential.isUserVerifyingPlatformAuthenticatorAvailable = exportFunction(
      function () {
        return pageWin.Promise.resolve(true);
      },
      pageWin,
    );
  }

  function sendToNative(message) {
    return browser.runtime.sendNativeMessage(NATIVE_APP, message);
  }

  function isInside(element, x, y) {
    const rect = element.getBoundingClientRect();
    return rect.width > 0 && rect.height > 0 &&
      x >= rect.left && x <= rect.right && y >= rect.top && y <= rect.bottom;
  }

  function imageUrlOf(element) {
    if (!element || !element.tagName) return null;
    const tag = element.tagName.toUpperCase();
    if (tag === "IMG") return element.currentSrc || element.src || null;
    if (tag === "IMAGE") {
      const href = element.getAttribute("href") || element.getAttribute("xlink:href");
      // SVG の href は相対パスのまま返るため絶対 URL に解決する
      return href ? new URL(href, document.baseURI).href : null;
    }
    const background = window.getComputedStyle(element).backgroundImage;
    if (background && background !== "none") {
      const match = background.match(/url\(["']?(.*?)["']?\)/);
      if (match && match[1]) return match[1];
    }
    return null;
  }

  // Amazon Photos は画像の上に透明な要素を重ねており、pointer-events: none の画像は
  // elementsFromPoint に含まれないため、重なっている要素の子孫も座標で探す。
  function findImageUrl(x, y) {
    const stack = document.elementsFromPoint(x, y) || [];
    for (const element of stack) {
      const url = imageUrlOf(element);
      if (url) return url;
    }
    for (const element of stack.slice(0, 8)) {
      for (const image of element.querySelectorAll("img")) {
        if (!isInside(image, x, y)) continue;
        const url = imageUrlOf(image);
        if (url) return url;
      }
    }
    return null;
  }

  function isScrolledFromTop(x, y) {
    let element = document.elementFromPoint(x, y);
    while (element && element !== document.documentElement) {
      if (element.scrollTop > 0) return true;
      element = element.parentElement;
    }
    const root = document.scrollingElement || document.documentElement;
    return !!(root && root.scrollTop > 0);
  }

  // コンテンツスクリプトの fetch はページのオリジンを含む権限で動くため blob: を読めるが、
  // 読めない環境に備えてページのコンテキストの fetch にも切り替える
  async function fetchBlob(url) {
    try {
      return await (await fetch(url)).blob();
    } catch (error) {
      console.warn("picker-bridge: fetch failed, retry in page context", String(error));
      return await (await content.fetch(url)).blob();
    }
  }

  // ページ由来の Blob の ArrayBuffer はサンドボックス越しに扱えないことがあるため、FileReader で文字列化する
  function readChunkAsBase64(blob, start, end) {
    return new Promise(function (resolve, reject) {
      const reader = new FileReader();
      reader.onload = function () {
        const dataUrl = reader.result;
        resolve(dataUrl.substring(dataUrl.indexOf(",") + 1));
      };
      reader.onerror = function () {
        reject(reader.error);
      };
      reader.readAsDataURL(blob.slice(start, end));
    });
  }

  // blob は巨大になりうるため、一度に文字列化せずチャンク単位でネイティブへ渡す
  // ネイティブがバックジェスチャー中かを contextmenu の発生時点で判定できるよう、取得より先に転送を始める
  async function transferBlob(url) {
    const token = await sendToNative({ type: "blobStart" });
    let blob;
    try {
      blob = await fetchBlob(url);
    } catch (error) {
      console.error("picker-bridge: blob fetch failed", String(error));
      // 空のまま終えると、ネイティブ側で一時ファイルを破棄して画像が見つからなかった扱いになる
      await sendToNative({ type: "blobEnd", token: token, mimeType: null });
      return;
    }
    for (let offset = 0; offset < blob.size; offset += BLOB_CHUNK_SIZE) {
      const end = Math.min(offset + BLOB_CHUNK_SIZE, blob.size);
      const data = await readChunkAsBase64(blob, offset, end);
      const written = await sendToNative({ type: "blobChunk", token: token, data: data });
      if (!written) return;
    }
    await sendToNative({ type: "blobEnd", token: token, mimeType: blob.type || null });
  }

  window.addEventListener("touchstart", function (event) {
    const touch = event.touches[0];
    if (!touch) return;
    sendToNative({ type: "scroll", scrolledFromTop: isScrolledFromTop(touch.clientX, touch.clientY) })
      .catch(function () {});
  }, { capture: true, passive: true });

  window.addEventListener("contextmenu", function (event) {
    const url = findImageUrl(event.clientX, event.clientY);
    if (url && url.startsWith("blob:")) {
      transferBlob(url).catch(function (error) {
        console.error("picker-bridge: blob transfer failed", String(error));
        sendToNative({ type: "image", url: null }).catch(function () {});
      });
      return;
    }
    sendToNative({ type: "image", url: url }).catch(function () {});
  }, true);
})();
