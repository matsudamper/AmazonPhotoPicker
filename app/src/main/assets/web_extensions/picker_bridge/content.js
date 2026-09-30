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

  function toBase64(buffer) {
    const bytes = new Uint8Array(buffer);
    let binary = "";
    for (let i = 0; i < bytes.length; i += 0x8000) {
      binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
    }
    return btoa(binary);
  }

  // blob は巨大になりうるため、一度に文字列化せずチャンク単位でネイティブへ渡す
  async function transferBlob(url) {
    // blob: URL はページのオリジンに属するため、ページのコンテキストで取得する
    const fetchInPage = typeof content !== "undefined" && content.fetch
      ? content.fetch.bind(content)
      : fetch;
    const blob = await (await fetchInPage(url)).blob();
    const token = await sendToNative({ type: "blobStart" });
    for (let offset = 0; offset < blob.size; offset += BLOB_CHUNK_SIZE) {
      const buffer = await blob.slice(offset, offset + BLOB_CHUNK_SIZE).arrayBuffer();
      const written = await sendToNative({ type: "blobChunk", token: token, data: toBase64(buffer) });
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
      transferBlob(url).catch(function () {
        sendToNative({ type: "image", url: null }).catch(function () {});
      });
      return;
    }
    sendToNative({ type: "image", url: url }).catch(function () {});
  }, true);
})();
