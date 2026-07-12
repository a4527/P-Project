const state = {
    config: null,
    campusMap: null,
    selectedBuildingId: null,
    selectedParkingSlotByLotId: new Map(),
    map: null,
    infoWindow: null,
    markers: [],
    markerByBuildingId: new Map(),
    authToken: localStorage.getItem("smartparking_token") ?? "",
    currentUser: null,
    currentParkingLocation: null,
    notifications: [],
    unreadNotificationCount: 0,
    alertRules: [],
    favoriteLotIds: new Set(),
};

const LOT_MAP_WIDTH = 854;
const LOT_MAP_HEIGHT = 480;

const elements = {
    configBadge: document.getElementById("config-badge"),
    updateBadge: document.getElementById("update-badge"),
    userBadge: document.getElementById("user-badge"),
    unreadBadge: document.getElementById("unread-badge"),
    buildingList: document.getElementById("building-list"),
    detailTitle: document.getElementById("detail-title"),
    detailSubtitle: document.getElementById("detail-subtitle"),
    detailContent: document.getElementById("detail-content"),
    mapFallback: document.getElementById("map-fallback"),
    authStatus: document.getElementById("auth-status"),
    loginForm: document.getElementById("login-form"),
    registerButton: document.getElementById("register-button"),
    logoutButton: document.getElementById("logout-button"),
    currentLocationPanel: document.getElementById("current-location-panel"),
    notificationList: document.getElementById("notification-list"),
    notificationCount: document.getElementById("notification-count"),
    favoriteList: document.getElementById("favorite-list"),
    favoriteCount: document.getElementById("favorite-count"),
};

document.addEventListener("DOMContentLoaded", () => {
    bindAuthActions();
    bindVoice();
    bootstrap().catch((error) => {
        console.error(error);
        elements.configBadge.textContent = "초기화 실패";
        elements.detailContent.innerHTML = `<div class="lot-card">화면을 불러오지 못했습니다.<br>${escapeHtml(error.message)}</div>`;
    });
});

async function bootstrap() {
    const [config, campusMap] = await Promise.all([
        fetchJson("/api/ui/config"),
        fetchJson("/api/campus/map"),
    ]);

    state.config = config;
    state.campusMap = campusMap;

    renderCampusHeader();
    renderBuildingList();
    renderFavoritePanel();

    await loadAuthenticatedSession();
    loadFavoritesForCurrentUser();
    renderAccountPanel();

    const firstBuildingId = campusMap.buildings?.[0]?.id;
    if (firstBuildingId) {
        await renderSelectedBuilding(firstBuildingId);
    }

    await renderMapIfPossible();
    await refreshUserPanels();
}

function renderCampusHeader() {
    elements.configBadge.textContent = state.config?.naverMapClientId
        ? "Naver Map 활성화"
        : "Naver Map 미설정";
    elements.updateBadge.textContent = `캠퍼스: ${state.config?.campus?.name ?? "미상"}`;
    if (elements.userBadge) {
        elements.userBadge.textContent = state.currentUser?.username
            ? `사용자: ${state.currentUser.username}`
            : "비로그인";
    }
    if (elements.unreadBadge) {
        elements.unreadBadge.textContent = `알림 ${state.unreadNotificationCount ?? 0}`;
    }
}

function renderAccountPanel() {
    if (elements.authStatus) {
        elements.authStatus.textContent = state.currentUser?.username
            ? `${state.currentUser.username}로 로그인됨`
            : "로그인이 필요합니다.";
    }

    if (elements.logoutButton) {
        elements.logoutButton.disabled = !state.currentUser;
    }

    renderCurrentLocationPanel();
    renderNotificationPanel();
    renderFavoritePanel();
}

async function loadAuthenticatedSession() {
    if (!state.authToken) {
        state.currentUser = null;
        state.currentParkingLocation = null;
        state.notifications = [];
        state.unreadNotificationCount = 0;
        state.alertRules = [];
        return;
    }

    try {
        const me = await fetchJson("/auth/me", {
            headers: authHeaders(),
        });
        state.currentUser = me;
        state.authToken = me.token ?? state.authToken;
        localStorage.setItem("smartparking_token", state.authToken);
        await refreshUserPanels();
    } catch (error) {
        clearAuthenticatedSession();
    }
}

function bindAuthActions() {
    if (elements.loginForm) {
        elements.loginForm.addEventListener("submit", async (event) => {
            event.preventDefault();
            const formData = new FormData(elements.loginForm);
            const username = String(formData.get("username") ?? "").trim();
            const password = String(formData.get("password") ?? "").trim();

            if (!username || !password) {
                alert("아이디와 비밀번호를 입력하세요.");
                return;
            }

            try {
                const result = await apiRequest("/auth/login", {
                    method: "POST",
                    body: JSON.stringify({ username, password }),
                });
                state.authToken = result.token;
                state.currentUser = { username: result.username ?? username };
                localStorage.setItem("smartparking_token", state.authToken);
                loadFavoritesForCurrentUser();
                await refreshUserPanels();
            } catch (error) {
                alert(error.message);
            }
        });
    }

    if (elements.registerButton) {
        elements.registerButton.addEventListener("click", async () => {
            const formData = new FormData(elements.loginForm);
            const username = String(formData.get("username") ?? "").trim();
            const password = String(formData.get("password") ?? "").trim();

            if (!username || !password) {
                alert("아이디와 비밀번호를 입력하세요.");
                return;
            }

            try {
                const result = await apiRequest("/auth/register", {
                    method: "POST",
                    body: JSON.stringify({ username, password }),
                });
                alert(result);
            } catch (error) {
                alert(error.message);
            }
        });
    }

    if (elements.logoutButton) {
        elements.logoutButton.addEventListener("click", () => {
            clearAuthenticatedSession();
        });
    }
}

async function refreshUserPanels() {
    if (!state.authToken || !state.currentUser?.username) {
        renderAccountPanel();
        return;
    }

    try {
        const [currentLocation, notifications, unreadCount, alertRules] = await Promise.all([
            fetchJson("/api/me/parking-location/current", { headers: authHeaders() }).catch(() => null),
            fetchJson("/api/me/notifications", { headers: authHeaders() }).catch(() => []),
            fetchJson("/api/me/notifications/unread-count", { headers: authHeaders() }).catch(() => ({ unreadCount: 0 })),
            fetchJson("/api/me/alert-rules", { headers: authHeaders() }).catch(() => []),
        ]);

        state.currentParkingLocation = currentLocation;
        state.notifications = Array.isArray(notifications) ? notifications : [];
        state.unreadNotificationCount = unreadCount?.unreadCount ?? 0;
        state.alertRules = Array.isArray(alertRules) ? alertRules : [];
    } catch (error) {
        console.error(error);
    }

    renderCampusHeader();
    renderAccountPanel();
}

function clearAuthenticatedSession() {
    state.authToken = "";
    state.currentUser = null;
    state.currentParkingLocation = null;
    state.notifications = [];
    state.unreadNotificationCount = 0;
    state.alertRules = [];
    loadFavoritesForCurrentUser();
    state.selectedParkingSlotByLotId.clear();
    localStorage.removeItem("smartparking_token");
    renderCampusHeader();
    renderAccountPanel();
}

function renderCurrentLocationPanel() {
    if (!elements.currentLocationPanel) {
        return;
    }

    const current = state.currentParkingLocation;
    if (!state.currentUser) {
        elements.currentLocationPanel.innerHTML = `
            <div class="panel-head">
                <h2>내 주차 위치</h2>
                <p>로그인 후 현재 주차 위치를 저장할 수 있습니다.</p>
            </div>
        `;
        return;
    }

    elements.currentLocationPanel.innerHTML = `
        <div class="panel-head">
            <h2>내 주차 위치</h2>
            <p>${current ? "저장된 위치를 확인할 수 있습니다." : "아직 저장된 위치가 없습니다."}</p>
        </div>
        <div class="location-card">
            <strong>${current ? `${escapeHtml(current.parkingLotName)} / 슬롯 ${escapeHtml(current.slotId)}` : "미저장"}</strong>
            <span>${current ? `차량: ${escapeHtml(current.vehicleLabel ?? "-")}` : "슬롯을 선택한 뒤 저장하세요."}</span>
            <span>${current ? `메모: ${escapeHtml(current.memo ?? "-")}` : ""}</span>
            <span>${current ? `저장 시각: ${formatDateTime(current.savedAt)}` : ""}</span>
        </div>
    `;
}

function renderNotificationPanel() {
    if (!elements.notificationList || !elements.notificationCount) {
        return;
    }

    elements.notificationCount.textContent = `${state.unreadNotificationCount ?? 0}개`;

    if (!state.currentUser) {
        elements.notificationList.innerHTML = `<li class="notification-empty">로그인하면 알림이 표시됩니다.</li>`;
        return;
    }

    if (!state.notifications.length) {
        elements.notificationList.innerHTML = `<li class="notification-empty">알림이 없습니다.</li>`;
        return;
    }

    elements.notificationList.innerHTML = state.notifications.map((notification) => `
        <li class="notification-item ${notification.read ? "read" : "unread"}" data-notification-id="${notification.id}">
            <div class="notification-title">${escapeHtml(notification.title)}</div>
            <div class="notification-message">${escapeHtml(notification.message)}</div>
            <div class="notification-meta">${formatDateTime(notification.createdAt)}</div>
        </li>
    `).join("");

    elements.notificationList.querySelectorAll("[data-notification-id]").forEach((item) => {
        item.addEventListener("click", async () => {
            const notificationId = item.dataset.notificationId;
            if (!notificationId) {
                return;
            }
            try {
                await apiRequest(`/api/me/notifications/${notificationId}/read`, {
                    method: "PATCH",
                    headers: authHeaders(),
                });
                await refreshUserPanels();
            } catch (error) {
                alert(error.message);
            }
        });
    });
}

function renderFavoritePanel() {
    if (!elements.favoriteList || !elements.favoriteCount) {
        return;
    }

    const favorites = collectFavoriteLots();
    elements.favoriteCount.textContent = `${favorites.length}개`;

    if (!favorites.length) {
        elements.favoriteList.innerHTML = `
            <div class="favorite-empty">
                아직 즐겨찾기한 주차장이 없습니다.
            </div>
        `;
        return;
    }

    elements.favoriteList.innerHTML = favorites.map(({ building, lot }) => {
        const summary = lot.summary ?? {};
        return `
            <button type="button" class="favorite-item" data-favorite-building="${building.id}" data-favorite-lot-target="${lot.id}">
                <span class="favorite-name">${escapeHtml(lot.name ?? "주차장")}</span>
                <span class="favorite-meta">${escapeHtml(building.name ?? "장소")} · ${summary.availableSlots ?? 0}/${summary.totalSlots ?? 0} 가능</span>
                <span class="pill ${getParkingStatusClass(summary)}">${getParkingStatusLabel(summary)}</span>
            </button>
        `;
    }).join("");

    elements.favoriteList.querySelectorAll("[data-favorite-building]").forEach((item) => {
        item.addEventListener("click", async () => {
            const buildingId = Number(item.dataset.favoriteBuilding);
            const lotId = Number(item.dataset.favoriteLotTarget);
            await renderSelectedBuilding(buildingId);
            document.querySelector(`[data-parking-lot-card="${lotId}"]`)?.scrollIntoView({
                behavior: "smooth",
                block: "start",
            });
        });
    });
}

function collectFavoriteLots() {
    const result = [];
    for (const building of state.campusMap?.buildings ?? []) {
        for (const lot of building.parkingLots ?? []) {
            if (state.favoriteLotIds.has(String(lot.id))) {
                result.push({ building, lot });
            }
        }
    }
    return result;
}

function favoriteStorageKey() {
    const username = state.currentUser?.username;
    return username
        ? `smartparking_favorite_lot_ids:${encodeURIComponent(username)}`
        : "smartparking_favorite_lot_ids:anonymous";
}

function loadFavoritesForCurrentUser() {
    state.favoriteLotIds = loadFavoriteLotIds();
    renderFavoritePanel();
    if (state.selectedBuildingId) {
        renderSelectedBuilding(state.selectedBuildingId);
    }
}

function loadFavoriteLotIds() {
    try {
        const raw = localStorage.getItem(favoriteStorageKey());
        const parsed = raw ? JSON.parse(raw) : [];
        return new Set(Array.isArray(parsed) ? parsed.map(String) : []);
    } catch (error) {
        return new Set();
    }
}

function saveFavoriteLotIds() {
    localStorage.setItem(favoriteStorageKey(), JSON.stringify([...state.favoriteLotIds]));
}

function toggleFavoriteLot(lotId) {
    const key = String(lotId);
    if (state.favoriteLotIds.has(key)) {
        state.favoriteLotIds.delete(key);
    } else {
        state.favoriteLotIds.add(key);
    }
    saveFavoriteLotIds();
}

function authHeaders() {
    return state.authToken
        ? { Authorization: `Bearer ${state.authToken}` }
        : {};
}

async function apiRequest(url, options = {}) {
    const headers = new Headers(options.headers ?? {});
    if (state.authToken) {
        headers.set("Authorization", `Bearer ${state.authToken}`);
    }
    if (options.body && !(options.body instanceof FormData) && !headers.has("Content-Type")) {
        headers.set("Content-Type", "application/json");
    }

    const response = await fetch(url, {
        ...options,
        headers,
    });

    if (!response.ok) {
        throw new Error(`${url} 요청 실패 (${response.status})`);
    }

    if (response.status === 204) {
        return null;
    }

    const contentType = response.headers.get("content-type") ?? "";
    if (contentType.includes("application/json")) {
        return response.json();
    }

    return response.text();
}

function renderBuildingList() {
    const buildings = state.campusMap?.buildings ?? [];

    elements.buildingList.innerHTML = buildings.map((building) => {
        const summary = summarizeParkingLots(building.parkingLots ?? []);
        const statusLabel = getParkingStatusLabel(summary);
        const pillClass = getParkingStatusClass(summary);

        return `
            <button class="building-card ${state.selectedBuildingId === building.id ? "selected" : ""}" data-building-id="${building.id}">
                <h3>${escapeHtml(building.name)}</h3>
                <div class="building-stats">
                    <span class="pill ${pillClass}">${escapeHtml(statusLabel)}</span>
                    <span class="pill">map ${escapeHtml(building.mapKey ?? "미지정")}</span>
                    <span class="pill">주차장 ${building.parkingLots?.length ?? 0}개</span>
                    <span class="pill">${summary ? `${summary.availableSlots}/${summary.totalSlots}` : "미등록"}</span>
                </div>
            </button>
        `;
    }).join("");

    elements.buildingList.querySelectorAll("[data-building-id]").forEach((button) => {
        button.addEventListener("click", () => {
            const buildingId = Number(button.dataset.buildingId);
            renderSelectedBuilding(buildingId);
        });
    });
}

async function renderSelectedBuilding(buildingId) {
    if (!buildingId) {
        return;
    }

    state.selectedBuildingId = buildingId;
    renderBuildingList();

    try {
        const detail = await fetchJson(`/api/campus/buildings/${buildingId}`);
        const building = detail.building;

        elements.detailTitle.textContent = building.name;
        elements.detailSubtitle.textContent = `${detail.campus.name} · ${building.mapKey ?? "map-key 없음"}`;

        const lots = detail.parkingLots ?? [];
        const lotsHtml = await renderParkingLotCards(lots);

        const addFormHtml = state.currentUser
            ? `
            <div class="lot-card add-lot-card">
                <h3>주차장 추가</h3>
                <form id="add-lot-form" class="add-lot-form">
                    <input name="name" type="text" placeholder="주차장 이름" required />
                    <label>영상(필수) <input name="video" type="file" accept="video/*" required /></label>
                    <label>사진(선택) <input name="image" type="file" accept="image/*" /></label>
                    <button type="submit">업로드</button>
                    <span id="add-lot-progress"></span>
                </form>
            </div>`
            : "";
        const deleteBuildingHtml = state.currentUser
            ? `<button type="button" class="danger building-delete-btn" data-delete-building="${building.id}">🗑 이 위치(건물) 삭제</button>`
            : "";
        elements.detailContent.innerHTML = addFormHtml + (lotsHtml || "<div class='lot-card'>주차장 정보가 없습니다.</div>") + deleteBuildingHtml;
        bindParkingLotActions(lots);
        bindBuildingDetailActions(building.id);
        elements.updateBadge.textContent = `갱신 시각: ${formatTimestamp(getLatestUpdate(lots))}`;

        focusMarker(buildingId);
    } catch (error) {
        elements.detailContent.innerHTML = `<div class="lot-card">장소 정보를 불러오지 못했습니다.<br>${escapeHtml(error.message)}</div>`;
    }
}

async function renderParkingLotCards(lots) {
    if (!lots.length) {
        return "";
    }

    const cards = await Promise.all(lots.map(async (lot) => renderParkingLotCard(lot)));
    return cards.join("");
}

async function renderParkingLotCard(lot) {
    const summary = lot.summary ?? {};
    const selectedSlotId = state.selectedParkingSlotByLotId.get(lot.id)
        ?? (state.currentParkingLocation?.parkingLotId === lot.id ? state.currentParkingLocation.slotId : null);
    const currentLocationMatch = state.currentParkingLocation?.parkingLotId === lot.id && state.currentParkingLocation.active;
        const mapHtml = renderParkingLotMap(lot);
    const favorite = state.favoriteLotIds.has(String(lot.id));

    return `
        <article class="lot-card parking-lot-card" data-parking-lot-card="${lot.id}">
            <div class="lot-header">
                <div>
                    <h3>${escapeHtml(lot.name)}</h3>
                    <p>파티션 ${escapeHtml(lot.partitionKey)} · 마지막 갱신 ${formatTimestamp(summary.lastUpdate)}</p>
                </div>
                <div class="building-stats">
                    <span class="pill ${getParkingStatusClass(summary)}">${getParkingStatusLabel(summary)}</span>
                    <span class="pill">${summary.availableSlots ?? 0}/${summary.totalSlots ?? 0} 가능</span>
                    <span class="pill">${summary.disabledAvailable ?? 0} 장애인석 가능</span>
                    <button type="button"
                        class="favorite-toggle ${favorite ? "active" : ""}"
                        data-favorite-lot="${lot.id}"
                        aria-pressed="${favorite ? "true" : "false"}">
                        ${favorite ? "★ 즐겨찾기" : "☆ 즐겨찾기"}
                    </button>
                </div>
            </div>
            <div class="detail-grid">
                <div class="metric">
                    <div class="metric-label">총 슬롯</div>
                    <div class="metric-value">${summary.totalSlots ?? 0}</div>
                </div>
                <div class="metric">
                    <div class="metric-label">사용 가능</div>
                    <div class="metric-value">${summary.availableSlots ?? 0}</div>
                </div>
                <div class="metric">
                    <div class="metric-label">장애인석 가능</div>
                    <div class="metric-value">${summary.disabledAvailable ?? 0}</div>
                </div>
                <div class="metric">
                    <div class="metric-label">상태</div>
                    <div class="metric-value">${getParkingStatusLabel(summary)}</div>
                </div>
            </div>
            <div class="parking-actions">
                <div class="parking-selection">
                    <span class="metric-label">선택 슬롯</span>
                    <strong data-selected-slot-display="${lot.id}">${selectedSlotId ?? "미선택"}</strong>
                    <span class="parking-active-hint ${currentLocationMatch ? "active" : ""}">
                        ${currentLocationMatch ? "현재 주차 위치" : "현재 저장 위치 없음"}
                    </span>
                </div>
                <div class="parking-action-row">
                    <button type="button" data-save-location-btn="${lot.id}" ${selectedSlotId ? "" : "disabled"}>내 위치 저장</button>
                    <button type="button" data-release-location-btn="${lot.id}" ${currentLocationMatch ? "" : "disabled"}>주차 종료</button>
                </div>
                <div class="parking-alert-row">
                    <input type="number" min="1" max="99" value="10" data-alert-threshold="${lot.id}" aria-label="알림 기준 자리 수">
                    <button type="button" data-create-alert-btn="${lot.id}">알림 등록</button>
                </div>
            </div>
            <section class="parking-lot-map-panel">
                <div class="parking-lot-map-head">
                    <div>
                        <div class="metric-label">주차장 맵</div>
                        <p>${lot.sourceImageExists
                            ? "업로드된 사진 위에 슬롯을 실제 위치대로 오버레이합니다."
                            : "사진을 업로드하면 여기에 실제 주차장 형태의 맵이 표시됩니다."}</p>
                    </div>
                    <div class="building-stats">
                        <span class="pill ${lot.slotLayoutJson ? "good" : "warn"}">${lot.slotLayoutJson ? "제작 완료" : "미제작"}</span>
                        <span class="pill ${lot.sourceImageExists ? "good" : "warn"}">${lot.sourceImageExists ? "사진 있음" : "사진 없음"}</span>
                    </div>
                    <div class="lot-map-legend">
                        <span class="legend-item legend-available">비어있음</span>
                        <span class="legend-item legend-full">사용중</span>
                        <span class="legend-item legend-disabled">비어있음(장애인석)</span>
                    </div>
                </div>
                ${mapHtml}
                ${state.currentUser
                    ? `<form class="lot-actions ${lot.sourceImageExists ? "lot-actions-compact" : ""}" data-lot-action-form>
                        <label class="lot-file-picker">
                            <span>${lot.sourceImageExists ? "사진 교체" : "사진 선택"}</span>
                            <input type="file" name="file" accept="image/*" required>
                        </label>
                        <button type="submit">${lot.sourceImageExists ? "다시 업로드" : "사진 업로드"}</button>
                        <button type="button" data-lot-editor-btn>웹 편집</button>
                        <button type="button" data-lot-refresh-btn>상태 새로고침</button>
                    </form>`
                    : `<div class="lot-actions-readonly">사진 업로드와 웹 슬롯 편집은 로그인 후 사용할 수 있습니다.</div>`}
                <p class="lot-helper">
                    ${lot.sourceImageExists
                        ? "슬롯 박스를 클릭해서 선택한 뒤 위치 저장과 알림 등록을 할 수 있습니다."
                        : "먼저 사진을 업로드한 뒤 웹 편집으로 슬롯을 배치하세요."}
                </p>
            </section>
            ${state.currentUser ? `<button type="button" class="danger" data-delete-lot="${lot.id}">주차장 삭제</button>` : ""}
        </article>
    `;
}

function renderParkingLotMap(lot) {
    if (!lot.sourceImageExists || !lot.sourceImageUrl) {
        return `
            <div class="lot-map-empty-state">
                <div class="lot-map-empty-copy">
                    <strong>주차장 사진이 없습니다.</strong>
                    <span>이 주차장의 실제 맵을 보려면 사진 업로드가 필요합니다.</span>
                </div>
            </div>
        `;
    }

    const layoutSlots = parseSlotLayout(lot.slotLayoutJson);
    if (!layoutSlots.length) {
        return `
            <div class="lot-map-stage">
                <img class="lot-map-bg" src="${lot.sourceImageUrl}" alt="${escapeHtml(lot.name)} 원본 사진">
                <div class="lot-map-overlay lot-map-overlay-empty">
                    <div class="lot-map-empty-copy">
                        <strong>슬롯 레이아웃이 아직 없습니다.</strong>
                        <span>사진은 업로드됐지만 매핑 정보가 없어서 오버레이를 표시할 수 없습니다.</span>
                    </div>
                </div>
            </div>
        `;
    }

    const slotById = new Map((lot.slots ?? []).map((slot) => [Number(slot.slotId), slot]));
    const selectedSlotId = state.selectedParkingSlotByLotId.get(lot.id)
        ?? (state.currentParkingLocation?.parkingLotId === lot.id ? state.currentParkingLocation.slotId : null);
    const slotBoxes = layoutSlots.map((layoutSlot, index) => {
        const slotId = Number(layoutSlot.slot ?? layoutSlot.slotId ?? index + 1);
        const liveSlot = slotById.get(slotId);
        return renderSlotBox(layoutSlot, slotId, liveSlot?.status, liveSlot?.type, lot.id, selectedSlotId);
    }).join("");

    return `
        <div class="lot-map-stage">
            <img class="lot-map-bg" src="${lot.sourceImageUrl}" alt="${escapeHtml(lot.name)} 원본 사진">
            <div class="lot-map-overlay">
                ${slotBoxes}
            </div>
        </div>
    `;
}

function renderSlotBox(layoutSlot, slotId, status, liveType, lotId, selectedSlotId) {
    const center = Array.isArray(layoutSlot.center) ? layoutSlot.center : [0, 0];
    const width = Number(layoutSlot.w ?? 40);
    const height = Number(layoutSlot.h ?? 70);
    const angle = Number(layoutSlot.angle ?? 0);
    const type = (liveType ?? layoutSlot.type ?? "normal").toString().toLowerCase();
    const isSelectedSlot = Number(slotId) === Number(selectedSlotId);
    const isCurrentLocation = state.currentParkingLocation?.parkingLotId === lotId
        && Number(state.currentParkingLocation?.slotId) === Number(slotId)
        && state.currentParkingLocation?.active;
    const statusClass = getSlotBoxClass(status, type, isSelectedSlot, isCurrentLocation);

    return `
        <div class="${statusClass}"
            data-parking-lot-slot="1"
            data-parking-lot-id="${lotId}"
            data-slot-id="${slotId}"
            style="
                left: ${(center[0] / LOT_MAP_WIDTH) * 100}%;
                top: ${(center[1] / LOT_MAP_HEIGHT) * 100}%;
                width: ${(width / LOT_MAP_WIDTH) * 100}%;
                height: ${(height / LOT_MAP_HEIGHT) * 100}%;
                transform: translate(-50%, -50%) rotate(${angle}deg);
            ">
            <span class="slot-box-number">${escapeHtml(slotId)}</span>
        </div>
    `;
}

function parseSlotLayout(value) {
    if (!value) {
        return [];
    }

    try {
        const parsed = JSON.parse(value);
        if (!Array.isArray(parsed)) {
            return [];
        }

        return parsed.slice().sort((a, b) => Number(a.slot ?? a.slotId ?? 0) - Number(b.slot ?? b.slotId ?? 0));
    } catch (error) {
        return [];
    }
}

function bindParkingLotActions(lots) {
    lots.forEach((lot) => {
        const card = document.querySelector(`[data-parking-lot-card="${lot.id}"]`);
        if (!card) {
            return;
        }

        const form = card.querySelector("[data-lot-action-form]");
        const editorButton = card.querySelector("[data-lot-editor-btn]");
        const refreshButton = card.querySelector("[data-lot-refresh-btn]");
        const saveLocationButton = card.querySelector(`[data-save-location-btn="${lot.id}"]`);
        const releaseLocationButton = card.querySelector(`[data-release-location-btn="${lot.id}"]`);
        const createAlertButton = card.querySelector(`[data-create-alert-btn="${lot.id}"]`);
        const alertThresholdInput = card.querySelector(`[data-alert-threshold="${lot.id}"]`);
        const favoriteButton = card.querySelector(`[data-favorite-lot="${lot.id}"]`);

        if (favoriteButton) {
            favoriteButton.addEventListener("click", () => {
                toggleFavoriteLot(lot.id);
                renderFavoritePanel();
                renderSelectedBuilding(state.selectedBuildingId);
            });
        }

        card.querySelectorAll("[data-parking-lot-slot]").forEach((slotBox) => {
            slotBox.addEventListener("click", () => {
                const slotId = Number(slotBox.dataset.slotId);
                state.selectedParkingSlotByLotId.set(lot.id, slotId);
                renderSelectedBuilding(state.selectedBuildingId);
            });
        });

        if (form) {
            form.addEventListener("submit", async (event) => {
                event.preventDefault();
                try {
                    const fileInput = form.querySelector('input[type="file"]');
                    const file = fileInput?.files?.[0];
                    if (!file) {
                        alert("업로드할 사진을 선택하세요.");
                        return;
                    }

                    const formData = new FormData();
                    formData.append("file", file);

                    const result = await apiRequest(`/api/parking-lots/${lot.id}/map/upload`, {
                        method: "POST",
                        body: formData,
                    });

                    elements.updateBadge.textContent = result.statusMessage ?? "사진 업로드가 완료되었습니다.";
                    await renderSelectedBuilding(state.selectedBuildingId);
                } catch (error) {
                    alert(error.message);
                }
            });
        }

        if (editorButton) {
            editorButton.addEventListener("click", () => openSlotEditor(lot));
        }

        if (refreshButton) {
            refreshButton.addEventListener("click", () => renderSelectedBuilding(state.selectedBuildingId));
        }

        if (saveLocationButton) {
            saveLocationButton.addEventListener("click", async () => {
                if (!state.authToken) {
                    alert("로그인이 필요합니다.");
                    return;
                }

                const selectedSlotId = state.selectedParkingSlotByLotId.get(lot.id);
                if (!selectedSlotId) {
                    alert("먼저 슬롯을 선택하세요.");
                    return;
                }

                const payload = {
                    parkingLotId: lot.id,
                    slotId: selectedSlotId,
                    vehicleLabel: prompt("차량 이름을 입력하세요", state.currentParkingLocation?.vehicleLabel ?? "") ?? "",
                    memo: prompt("메모를 입력하세요", state.currentParkingLocation?.memo ?? "") ?? "",
                };

                try {
                    await apiRequest("/api/me/parking-location", {
                        method: "POST",
                        headers: authHeaders(),
                        body: JSON.stringify(payload),
                    });
                    await refreshUserPanels();
                    await renderSelectedBuilding(state.selectedBuildingId);
                } catch (error) {
                    alert(error.message);
                }
            });
        }

        if (releaseLocationButton) {
            releaseLocationButton.addEventListener("click", async () => {
                if (!state.authToken) {
                    alert("로그인이 필요합니다.");
                    return;
                }

                try {
                    await apiRequest("/api/me/parking-location/current", {
                        method: "DELETE",
                        headers: authHeaders(),
                    });
                    await refreshUserPanels();
                    await renderSelectedBuilding(state.selectedBuildingId);
                } catch (error) {
                    alert(error.message);
                }
            });
        }

        if (createAlertButton) {
            createAlertButton.addEventListener("click", async () => {
                if (!state.authToken) {
                    alert("로그인이 필요합니다.");
                    return;
                }

                const threshold = Number(alertThresholdInput?.value ?? 10);
                if (!threshold || Number.isNaN(threshold)) {
                    alert("알림 기준을 입력하세요.");
                    return;
                }

                try {
                    await apiRequest("/api/me/alert-rules", {
                        method: "POST",
                        headers: authHeaders(),
                        body: JSON.stringify({
                            parkingLotId: lot.id,
                            minimumAvailableSlots: threshold,
                            enabled: true,
                        }),
                    });
                    await refreshUserPanels();
                } catch (error) {
                    alert(error.message);
                }
            });
        }
    });

    document.querySelectorAll("[data-delete-lot]").forEach((btn) => {
        btn.addEventListener("click", async () => {
            const lotId = Number(btn.dataset.deleteLot);
            if (!window.confirm("이 주차장을 삭제할까요?")) {
                return;
            }
            try {
                await apiRequest(`/api/parking-lots/${lotId}`, { method: "DELETE" });
                if (state.selectedBuildingId) {
                    await renderSelectedBuilding(state.selectedBuildingId);
                }
            } catch (error) {
                window.alert(`주차장 삭제 실패: ${error.message}`);
            }
        });
    });
}

async function renderMapIfPossible() {
    const clientId = state.config?.naverMapClientId?.trim();
    if (!clientId) {
        elements.mapFallback.classList.remove("hidden");
        elements.mapFallback.innerHTML = "네이버 지도 클라이언트 ID가 설정되지 않았습니다.<br>장소 선택과 상태 확인은 계속 사용할 수 있습니다.";
        return;
    }

    await loadNaverMapScript(clientId);
    createNaverMap();
}

async function loadNaverMapScript(clientId) {
    if (window.naver?.maps) {
        return;
    }

    await new Promise((resolve, reject) => {
        const script = document.createElement("script");
        script.type = "text/javascript";
        script.src = `https://oapi.map.naver.com/openapi/v3/maps.js?ncpKeyId=${encodeURIComponent(clientId)}&submodules=geocoder&language=ko`;
        script.onload = resolve;
        script.onerror = () => reject(new Error("Naver Map script load failed"));
        document.head.appendChild(script);
    });
}

function createNaverMap() {
    const campus = state.config?.campus;
    if (!campus || !window.naver?.maps) {
        return;
    }

    const center = new naver.maps.LatLng(campus.centerLat, campus.centerLng);
    state.map = new naver.maps.Map("map", {
        center,
        zoom: campus.defaultZoom ?? 16,
        mapTypeControl: false,
        scaleControl: false,
    });
    state.infoWindow = new naver.maps.InfoWindow({
        content: "",
        borderWidth: 1,
        backgroundColor: "#ffffff",
        anchorSize: new naver.maps.Size(10, 10),
    });

    state.markers = (state.campusMap?.buildings ?? []).map((building) => {
        const marker = new naver.maps.Marker({
            position: new naver.maps.LatLng(building.lat, building.lng),
            map: state.map,
            title: building.name,
        });

        naver.maps.Event.addListener(marker, "click", () => renderSelectedBuilding(building.id));
        state.markerByBuildingId.set(building.id, marker);
        return marker;
    });

    naver.maps.Event.addListener(state.map, "click", (e) => {
        if (!state.currentUser) {
            window.alert("장소 등록은 로그인 후 가능합니다.");
            return;
        }
        promptCreateBuilding(e.coord.lat(), e.coord.lng());
    });

    elements.mapFallback.classList.add("hidden");

    if (state.selectedBuildingId) {
        focusMarker(state.selectedBuildingId);
    }

    bindMapSearch();
}

function focusMarker(buildingId) {
    if (!state.map || !window.naver?.maps) {
        return;
    }

    const building = (state.campusMap?.buildings ?? []).find((item) => item.id === buildingId);
    if (!building) {
        return;
    }

    const latLng = new naver.maps.LatLng(building.lat, building.lng);
    state.map.setCenter(latLng);
    state.map.setZoom(Math.max(17, state.config?.campus?.defaultZoom ?? 16));

    if (state.infoWindow) {
        state.infoWindow.setContent(`
            <div style="padding:8px 10px; min-width:160px;">
                <strong>${escapeHtml(building.name)}</strong><br>
                <span style="color:#667085;">${escapeHtml(building.mapKey ?? "")}</span>
            </div>
        `);
        const marker = state.markerByBuildingId.get(building.id);
        if (marker) {
            state.infoWindow.open(state.map, marker);
        }
    }
}

async function promptCreateBuilding(lat, lng) {
    const name = window.prompt("장소 이름을 입력하세요");
    if (name === null || name.trim() === "") {
        return;
    }
    try {
        await apiRequest("/api/buildings", {
            method: "POST",
            body: JSON.stringify({ name: name.trim(), lat, lng }),
        });
        state.campusMap = await fetchJson("/api/campus/map");
        renderBuildingList();
        recreateMarkers();
    } catch (error) {
        window.alert(`장소 등록 실패: ${error.message}`);
    }
}

function recreateMarkers() {
    if (!state.map || !window.naver?.maps) {
        return;
    }
    state.markers.forEach((m) => m.setMap(null));
    state.markerByBuildingId.clear();
    state.markers = (state.campusMap?.buildings ?? []).map((building) => {
        const marker = new naver.maps.Marker({
            position: new naver.maps.LatLng(building.lat, building.lng),
            map: state.map,
            title: building.name,
        });
        naver.maps.Event.addListener(marker, "click", () => renderSelectedBuilding(building.id));
        state.markerByBuildingId.set(building.id, marker);
        return marker;
    });
}

function bindMapSearch() {
    const input = document.getElementById("map-search-input");
    const button = document.getElementById("map-search-button");
    if (!input || !button) {
        return;
    }
    const run = () => searchAndMove(input.value.trim());
    button.addEventListener("click", run);
    input.addEventListener("keydown", (e) => {
        if (e.key === "Enter") {
            run();
        }
    });
}

async function searchAndMove(query) {
    if (!query || !state.map || !window.naver?.maps) {
        return;
    }
    try {
        const results = await fetchJson(`/api/geo/search?query=${encodeURIComponent(query)}`);
        if (!results || !results.length) {
            window.alert("검색 결과가 없습니다.");
            return;
        }
        const item = results[0];
        const latLng = new naver.maps.LatLng(item.lat, item.lng);
        state.map.setCenter(latLng);
        state.map.setZoom(18);
    } catch (error) {
        window.alert(`검색 실패: ${error.message}`);
    }
}

async function fetchJson(url, options = {}) {
    const response = await fetch(url, options);
    if (!response.ok) {
        throw new Error(`${url} 요청 실패 (${response.status})`);
    }
    if (response.status === 204) {
        return null;
    }
    return response.json();
}

function statusText(status) {
    const availableSlots = extractAvailableSlots(status);

    if (availableSlots === null) {
        return "준비중";
    }

    if (availableSlots >= 10) {
        return "여유";
    }

    if (availableSlots >= 5) {
        return "보통";
    }

    return "혼잡";
}

function getParkingStatusLabel(status) {
    return statusText(status);
}

function getParkingStatusClass(status) {
    const availableSlots = extractAvailableSlots(status);

    if (availableSlots === null) {
        return "";
    }

    if (availableSlots >= 10) {
        return "good";
    }

    if (availableSlots >= 5) {
        return "warn";
    }

    return "bad";
}

function extractAvailableSlots(value) {
    if (typeof value === "number" && Number.isFinite(value)) {
        return value;
    }

    if (!value || typeof value !== "object") {
        return null;
    }

    const availableSlots = value.availableSlots;
    return typeof availableSlots === "number" && Number.isFinite(availableSlots)
        ? availableSlots
        : null;
}

function summarizeParkingLots(parkingLots) {
    if (!Array.isArray(parkingLots) || parkingLots.length === 0) {
        return null;
    }

    let availableSlots = 0;
    let totalSlots = 0;
    let disabledAvailable = 0;
    let hasSummary = false;

    for (const lot of parkingLots) {
        const summary = lot?.summary;
        if (!summary) {
            continue;
        }

        hasSummary = true;
        availableSlots += Number(summary.availableSlots ?? 0);
        totalSlots += Number(summary.totalSlots ?? 0);
        disabledAvailable += Number(summary.disabledAvailable ?? 0);
    }

    if (!hasSummary) {
        return null;
    }

    return {
        availableSlots,
        totalSlots,
        disabledAvailable,
    };
}

function openSlotEditor(lot) {
    if (!lot.sourceImageExists || !lot.sourceImageUrl) {
        alert("먼저 주차장 사진을 업로드하세요.");
        return;
    }

    const editorState = {
        lot,
        slots: parseSlotLayout(lot.slotLayoutJson).map((slot, index) => normalizeEditorSlot(slot, index)),
        selectedIndex: null,
        draggingIndex: null,
        lastSlotShape: { w: 48, h: 78, angle: 0 },
    };
    if (editorState.slots.length > 0) {
        editorState.selectedIndex = 0;
        const lastSlot = editorState.slots[editorState.slots.length - 1];
        editorState.lastSlotShape = {
            w: lastSlot.w,
            h: lastSlot.h,
            angle: lastSlot.angle,
        };
    }

    const modal = document.createElement("div");
    modal.className = "slot-editor-modal";
    modal.innerHTML = `
        <div class="slot-editor-dialog" role="dialog" aria-modal="true" aria-label="슬롯 편집">
            <div class="slot-editor-head">
                <div>
                    <h3>${escapeHtml(lot.name)} 슬롯 편집</h3>
                    <p>사진을 클릭해 슬롯을 추가하고, 슬롯을 드래그해 위치를 조정합니다.</p>
                </div>
                <button type="button" class="slot-editor-close" data-editor-close>닫기</button>
            </div>
            <div class="slot-editor-body">
                <div class="slot-editor-stage" data-editor-stage>
                    <img src="${lot.sourceImageUrl}" alt="${escapeHtml(lot.name)} 원본 사진">
                    <div class="slot-editor-overlay" data-editor-overlay></div>
                </div>
                <aside class="slot-editor-panel">
                    <div class="slot-editor-actions">
                        <button type="button" data-editor-add>슬롯 추가</button>
                        <button type="button" data-editor-delete>선택 삭제</button>
                        <button type="button" data-editor-save>저장</button>
                    </div>
                    <label>슬롯 번호 <input type="number" min="1" data-editor-slot></label>
                    <label>너비 <input type="number" min="8" max="240" data-editor-width></label>
                    <label>높이 <input type="number" min="8" max="240" data-editor-height></label>
                    <label>각도 <input type="number" min="-180" max="180" data-editor-angle></label>
                    <label>유형
                        <select data-editor-type>
                            <option value="normal">일반</option>
                            <option value="disabled">장애인석</option>
                        </select>
                    </label>
                    <p class="slot-editor-hint">저장 후 FastAPI가 이 슬롯 정보를 기준으로 영상을 분석합니다.</p>
                </aside>
            </div>
        </div>
    `;
    document.body.appendChild(modal);

    const stage = modal.querySelector("[data-editor-stage]");
    const overlay = modal.querySelector("[data-editor-overlay]");
    const controls = {
        slot: modal.querySelector("[data-editor-slot]"),
        width: modal.querySelector("[data-editor-width]"),
        height: modal.querySelector("[data-editor-height]"),
        angle: modal.querySelector("[data-editor-angle]"),
        type: modal.querySelector("[data-editor-type]"),
    };

    const syncEditorControls = () => {
        const selected = editorState.slots[editorState.selectedIndex];
        const disabled = !selected;
        Object.values(controls).forEach((control) => {
            control.disabled = disabled;
        });
        controls.slot.value = selected ? selected.slot : "";
        controls.width.value = selected ? Math.round(selected.w) : "";
        controls.height.value = selected ? Math.round(selected.h) : "";
        controls.angle.value = selected ? Math.round(selected.angle) : "";
        controls.type.value = selected ? selected.type : "normal";
    };

    const bindEditorSlotBoxes = () => {
        overlay.querySelectorAll("[data-editor-slot-index]").forEach((box) => {
            box.addEventListener("pointerdown", (event) => {
                event.preventDefault();
                editorState.selectedIndex = Number(box.dataset.editorSlotIndex);
                editorState.draggingIndex = editorState.selectedIndex;
                box.setPointerCapture(event.pointerId);
                render();
            });
            box.addEventListener("pointermove", (event) => {
                if (editorState.draggingIndex === null) {
                    return;
                }
                moveSelectedSlotToPointer(stage, editorState, event);
                render();
            });
            box.addEventListener("pointerup", () => {
                editorState.draggingIndex = null;
            });
            box.addEventListener("pointercancel", () => {
                editorState.draggingIndex = null;
            });
        });
    };

    const render = () => {
        overlay.innerHTML = editorState.slots.map((slot, index) => `
            <button type="button"
                class="slot-editor-box ${index === editorState.selectedIndex ? "selected" : ""} ${slot.type === "disabled" ? "disabled" : ""}"
                data-editor-slot-index="${index}"
                style="
                    left: ${(slot.center[0] / LOT_MAP_WIDTH) * 100}%;
                    top: ${(slot.center[1] / LOT_MAP_HEIGHT) * 100}%;
                    width: ${(slot.w / LOT_MAP_WIDTH) * 100}%;
                    height: ${(slot.h / LOT_MAP_HEIGHT) * 100}%;
                    transform: translate(-50%, -50%) rotate(${slot.angle}deg);
                ">
                ${escapeHtml(slot.slot)}
            </button>
        `).join("");
        bindEditorSlotBoxes();
        syncEditorControls();
    };

    const updateSelected = () => {
        const selected = editorState.slots[editorState.selectedIndex];
        if (!selected) {
            return;
        }
        selected.slot = clamp(Number(controls.slot.value), 1, 999);
        selected.w = clamp(Number(controls.width.value), 8, 240);
        selected.h = clamp(Number(controls.height.value), 8, 240);
        selected.angle = clamp(Number(controls.angle.value), -180, 180);
        selected.type = controls.type.value === "disabled" ? "disabled" : "normal";
        editorState.lastSlotShape = {
            w: selected.w,
            h: selected.h,
            angle: selected.angle,
        };
        render();
    };

    Object.values(controls).forEach((control) => {
        control.addEventListener("input", updateSelected);
        control.addEventListener("change", updateSelected);
    });

    stage.addEventListener("click", (event) => {
        if (event.target.closest("[data-editor-slot-index]")) {
            return;
        }
        const point = pointerToMapPoint(stage, event);
        const shape = currentSlotShape(editorState);
        editorState.slots.push({
            slot: nextSlotNumber(editorState.slots),
            center: [point.x, point.y],
            w: shape.w,
            h: shape.h,
            angle: shape.angle,
            type: "normal",
        });
        editorState.selectedIndex = editorState.slots.length - 1;
        render();
    });

    modal.querySelector("[data-editor-add]").addEventListener("click", () => {
        const shape = currentSlotShape(editorState);
        editorState.slots.push({
            slot: nextSlotNumber(editorState.slots),
            center: [LOT_MAP_WIDTH / 2, LOT_MAP_HEIGHT / 2],
            w: shape.w,
            h: shape.h,
            angle: shape.angle,
            type: "normal",
        });
        editorState.selectedIndex = editorState.slots.length - 1;
        render();
    });

    modal.querySelector("[data-editor-delete]").addEventListener("click", () => {
        if (editorState.selectedIndex === null) {
            return;
        }
        editorState.slots.splice(editorState.selectedIndex, 1);
        editorState.selectedIndex = editorState.slots.length ? Math.min(editorState.selectedIndex, editorState.slots.length - 1) : null;
        render();
    });

    modal.querySelector("[data-editor-save]").addEventListener("click", async () => {
        if (!editorState.slots.length) {
            alert("저장할 슬롯이 없습니다.");
            return;
        }
        const payload = editorState.slots
            .slice()
            .sort((a, b) => Number(a.slot) - Number(b.slot))
            .map((slot) => ({
                slot: Number(slot.slot),
                center: [round1(slot.center[0]), round1(slot.center[1])],
                w: round1(slot.w),
                h: round1(slot.h),
                angle: round1(slot.angle),
                type: slot.type,
            }));
        try {
            const result = await apiRequest(`/api/parking-lots/${lot.id}/map/slots`, {
                method: "POST",
                body: JSON.stringify({ slotLayoutJson: JSON.stringify(payload) }),
            });
            elements.updateBadge.textContent = result.statusMessage ?? "슬롯 레이아웃을 저장했습니다.";
            modal.remove();
            await renderSelectedBuilding(state.selectedBuildingId);
        } catch (error) {
            alert(error.message);
        }
    });

    modal.querySelector("[data-editor-close]").addEventListener("click", () => modal.remove());
    modal.addEventListener("click", (event) => {
        if (event.target === modal) {
            modal.remove();
        }
    });

    render();
}

function normalizeEditorSlot(slot, index) {
    const center = Array.isArray(slot.center) ? slot.center : [LOT_MAP_WIDTH / 2, LOT_MAP_HEIGHT / 2];
    return {
        slot: Number(slot.slot ?? slot.slotId ?? index + 1),
        center: [
            clamp(Number(center[0]), 0, LOT_MAP_WIDTH),
            clamp(Number(center[1]), 0, LOT_MAP_HEIGHT),
        ],
        w: clamp(Number(slot.w ?? 48), 8, 240),
        h: clamp(Number(slot.h ?? 78), 8, 240),
        angle: clamp(Number(slot.angle ?? 0), -180, 180),
        type: slot.type === "disabled" ? "disabled" : "normal",
    };
}

function currentSlotShape(editorState) {
    const selected = editorState.slots[editorState.selectedIndex];
    if (selected) {
        return {
            w: selected.w,
            h: selected.h,
            angle: selected.angle,
        };
    }
    return editorState.lastSlotShape;
}

function pointerToMapPoint(stage, event) {
    const rect = stage.getBoundingClientRect();
    return {
        x: clamp(((event.clientX - rect.left) / rect.width) * LOT_MAP_WIDTH, 0, LOT_MAP_WIDTH),
        y: clamp(((event.clientY - rect.top) / rect.height) * LOT_MAP_HEIGHT, 0, LOT_MAP_HEIGHT),
    };
}

function moveSelectedSlotToPointer(stage, editorState, event) {
    const selected = editorState.slots[editorState.draggingIndex];
    if (!selected) {
        return;
    }
    const point = pointerToMapPoint(stage, event);
    selected.center = [point.x, point.y];
}

function nextSlotNumber(slots) {
    const used = new Set(slots.map((slot) => Number(slot.slot)));
    let n = 1;
    while (used.has(n)) {
        n += 1;
    }
    return n;
}

function clamp(value, min, max) {
    if (!Number.isFinite(value)) {
        return min;
    }
    return Math.min(max, Math.max(min, value));
}

function round1(value) {
    return Math.round(Number(value) * 10) / 10;
}

function getSlotBoxClass(status, type, selected = false, currentLocation = false) {
    const normalizedStatus = (status ?? "").toString().toLowerCase();
    const normalizedType = (type ?? "").toString().toLowerCase();
    const disabledClass = normalizedType === "disabled" ? "slot-disabled" : "slot-normal";

    const baseClass = normalizedStatus === "available"
        ? "slot-available"
        : (normalizedStatus === "occupied" || normalizedStatus === "full")
            ? "slot-full"
            : "slot-unknown";

    const selectedClass = selected ? "slot-selected" : "";
    const currentClass = currentLocation ? "slot-current-location" : "";
    return `slot-box ${baseClass} ${disabledClass} ${selectedClass} ${currentClass}`.trim();
}

function getLatestUpdate(lots) {
    return (lots ?? [])
        .map((lot) => lot?.summary?.lastUpdate)
        .filter((value) => typeof value === "number" && !Number.isNaN(value))
        .sort((a, b) => b - a)[0] ?? null;
}

function slotStatusClass(status) {
    switch ((status ?? "").toString().toLowerCase()) {
        case "available":
            return "status-available";
        case "occupied":
        case "full":
            return "status-full";
        default:
            return "status-unknown";
    }
}

function formatTimestamp(value) {
    if (!value) {
        return "갱신 대기 중";
    }

    const date = new Date(value * 1000);
    return new Intl.DateTimeFormat("ko-KR", {
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
        second: "2-digit",
    }).format(date);
}

function formatDateTime(value) {
    if (!value) {
        return "";
    }

    const date = new Date(value);
    return new Intl.DateTimeFormat("ko-KR", {
        month: "2-digit",
        day: "2-digit",
        hour: "2-digit",
        minute: "2-digit",
    }).format(date);
}

function prettyJson(value) {
    try {
        return JSON.stringify(JSON.parse(value), null, 2);
    } catch (error) {
        return value;
    }
}

function escapeHtml(value) {
    return String(value ?? "")
        .replaceAll("&", "&amp;")
        .replaceAll("<", "&lt;")
        .replaceAll(">", "&gt;")
        .replaceAll('"', "&quot;")
        .replaceAll("'", "&#39;");
}

function bindBuildingDetailActions(buildingId) {
    const form = document.getElementById("add-lot-form");
    if (form) {
        form.addEventListener("submit", (e) => {
            e.preventDefault();
            submitAddParkingLot(buildingId, form);
        });
    }
    const deleteBuildingBtn = document.querySelector(`[data-delete-building="${buildingId}"]`);
    if (deleteBuildingBtn) {
        deleteBuildingBtn.addEventListener("click", () => deleteBuilding(buildingId));
    }
}

async function submitAddParkingLot(buildingId, form) {
    const progress = document.getElementById("add-lot-progress");
    const data = new FormData();
    data.set("name", form.elements.name.value.trim());
    if (form.elements.video.files[0]) {
        data.set("video", form.elements.video.files[0]);
    }
    if (form.elements.image.files[0]) {
        data.set("image", form.elements.image.files[0]);
    }
    try {
        if (progress) {
            progress.textContent = "업로드 중...";
        }
        await apiRequest(`/api/buildings/${buildingId}/parking-lots`, {
            method: "POST",
            body: data,
        });
        if (progress) {
            progress.textContent = "완료";
        }
        await renderSelectedBuilding(buildingId);
    } catch (error) {
        if (progress) {
            progress.textContent = "";
        }
        window.alert(`주차장 추가 실패: ${error.message}`);
    }
}

async function deleteBuilding(buildingId) {
    if (!window.confirm("이 장소과 하위 주차장을 모두 삭제할까요?")) {
        return;
    }
    try {
        await apiRequest(`/api/buildings/${buildingId}`, { method: "DELETE" });
        state.campusMap = await fetchJson("/api/campus/map");
        state.selectedBuildingId = null;
        renderBuildingList();
        recreateMarkers();
        elements.detailContent.innerHTML = "<div class='lot-card'>장소을 선택하세요.</div>";
    } catch (error) {
        window.alert(`장소 삭제 실패: ${error.message}`);
    }
}

function getSpeechRecognition() {
    return window.SpeechRecognition || window.webkitSpeechRecognition || null;
}

function bindVoice() {
    const button = document.getElementById("voice-button");
    const askButton = document.getElementById("voice-ask-button");
    const input = document.getElementById("voice-question-input");
    const output = document.getElementById("voice-output");
    if (!button && !askButton) {
        return;
    }
    const Recognition = getSpeechRecognition();
    if (button && !Recognition) {
        button.disabled = true;
        button.textContent = "🎤 음성 미지원 브라우저";
    }
    if (button && Recognition) {
        button.addEventListener("click", () => startVoiceQuery(button, output, Recognition));
    }
    if (askButton && input) {
        askButton.addEventListener("click", () => askVoiceQuestion(input.value, output, askButton));
        input.addEventListener("keydown", (event) => {
            if (event.key === "Enter") {
                askVoiceQuestion(input.value, output, askButton);
            }
        });
    }
}

function startVoiceQuery(button, output, Recognition) {
    const recognition = new Recognition();
    recognition.lang = "ko-KR";
    recognition.continuous = false;
    recognition.interimResults = true;
    recognition.maxAlternatives = 3;

    let finalQuestion = "";
    let answerRequested = false;

    button.disabled = true;
    if (output) {
        output.textContent = "🎙️ 듣는 중...";
    }

    recognition.onresult = async (event) => {
        let transcript = "";
        for (let index = event.resultIndex; index < event.results.length; index += 1) {
            transcript += event.results[index][0].transcript;
        }

        const result = event.results[event.results.length - 1];
        if (!result.isFinal) {
            if (output) {
                output.textContent = `듣는 중: ${transcript}`;
            }
            return;
        }

        finalQuestion = transcript.trim();
        if (!finalQuestion || answerRequested) {
            return;
        }
        answerRequested = true;
        const input = document.getElementById("voice-question-input");
        if (input) {
            input.value = finalQuestion;
        }
        if (output) {
            output.textContent = `질문: ${finalQuestion} (답변 생성 중...)`;
        }
        try {
            const answer = await requestVoiceAnswer(finalQuestion);
            if (output) {
                output.textContent = `Q: ${finalQuestion} / A: ${answer}`;
            }
            speak(answer);
        } catch (error) {
            if (output) {
                output.textContent = `오류: ${error.message}`;
            }
        } finally {
            button.disabled = false;
        }
    };

    recognition.onerror = (event) => {
        if (output) {
            output.textContent = speechRecognitionErrorMessage(event.error);
        }
        button.disabled = false;
    };

    recognition.onend = () => {
        button.disabled = false;
    };

    try {
        recognition.start();
    } catch (error) {
        button.disabled = false;
        if (output) {
            output.textContent = "마이크를 시작하지 못했습니다. 브라우저의 마이크 권한을 확인해 주세요.";
        }
    }
}

function speechRecognitionErrorMessage(error) {
    switch (error) {
        case "not-allowed":
        case "service-not-allowed":
            return "마이크 권한이 거부되었습니다. 주소창의 사이트 설정에서 마이크를 허용해 주세요.";
        case "audio-capture":
            return "사용할 수 있는 마이크가 없습니다. 마이크 연결과 시스템 권한을 확인해 주세요.";
        case "no-speech":
            return "음성이 감지되지 않았습니다. 마이크에 가까이 말한 뒤 다시 시도해 주세요.";
        case "network":
            return "음성 인식 서버에 연결하지 못했습니다. 네트워크를 확인해 주세요.";
        case "aborted":
            return "음성 인식이 중단되었습니다. 다시 시도해 주세요.";
        default:
            return `음성 인식 오류(${error ?? "unknown"})입니다. 다시 시도해 주세요.`;
    }
}

async function askVoiceQuestion(question, output, button) {
    const normalized = String(question ?? "").trim();
    if (!normalized) {
        if (output) {
            output.textContent = "질문을 입력하세요.";
        }
        return;
    }

    if (button) {
        button.disabled = true;
    }
    if (output) {
        output.textContent = "답변 생성 중...";
    }

    try {
        const answer = await requestVoiceAnswer(normalized);
        if (output) {
            output.textContent = `Q: ${normalized} / A: ${answer}`;
        }
        speak(answer);
    } catch (error) {
        if (output) {
            output.textContent = `오류: ${error.message}`;
        }
    } finally {
        if (button) {
            button.disabled = false;
        }
    }
}

async function requestVoiceAnswer(question) {
    const result = await apiRequest("/api/voice/ask", {
        method: "POST",
        body: JSON.stringify({ question }),
    });
    return result?.answer ?? "답변을 받지 못했어요.";
}

function speak(text) {
    if (!("speechSynthesis" in window)) {
        return;
    }
    const utterance = new SpeechSynthesisUtterance(text);
    utterance.lang = "ko-KR";
    window.speechSynthesis.cancel();
    window.speechSynthesis.speak(utterance);
}
