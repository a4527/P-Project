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
    polygonDraftByLotId: new Map(),
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
};

document.addEventListener("DOMContentLoaded", () => {
    bindAuthActions();
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

    await loadAuthenticatedSession();
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

        elements.detailContent.innerHTML = lotsHtml || "<div class='lot-card'>주차장 정보가 없습니다.</div>";
        bindParkingLotActions(lots);
        bindPolygonEditors(lots);
        elements.updateBadge.textContent = `갱신 시각: ${formatTimestamp(getLatestUpdate(lots))}`;

        focusMarker(buildingId);
    } catch (error) {
        elements.detailContent.innerHTML = `<div class="lot-card">건물 정보를 불러오지 못했습니다.<br>${escapeHtml(error.message)}</div>`;
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
    const polygonDraft = await loadPolygonDraftForLot(lot.id);
    const mapHtml = renderParkingLotMap(lot);
    const polygonEditorHtml = lot.sourceImageExists ? renderPolygonEditor(lot, polygonDraft) : "";

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
                        <span class="pill ${lot.generatedMapExists ? "good" : "warn"}">${lot.generatedMapExists ? "제작 완료" : "미제작"}</span>
                        <span class="pill ${lot.sourceImageExists ? "good" : "warn"}">${lot.sourceImageExists ? "사진 있음" : "사진 없음"}</span>
                    </div>
                    <div class="lot-map-legend">
                        <span class="legend-item legend-available">비어있음</span>
                        <span class="legend-item legend-full">사용중</span>
                        <span class="legend-item legend-disabled">비어있음(장애인석)</span>
                    </div>
                </div>
                ${mapHtml}
                <form class="lot-actions ${lot.sourceImageExists ? "lot-actions-compact" : ""}" data-lot-action-form>
                    <label class="lot-file-picker">
                        <span>${lot.sourceImageExists ? "사진 교체" : "사진 선택"}</span>
                        <input type="file" name="file" accept="image/*" required>
                    </label>
                    <button type="submit">${lot.sourceImageExists ? "다시 업로드" : "사진 업로드"}</button>
                    <button type="button" data-lot-build-btn>지도 제작하기</button>
                    <button type="button" data-lot-refresh-btn>상태 새로고침</button>
                </form>
                <p class="lot-helper">
                    ${lot.sourceImageExists
                        ? "슬롯 박스를 클릭해서 선택한 뒤 위치 저장과 알림 등록을 할 수 있습니다."
                        : "먼저 사진을 업로드한 뒤 지도 제작을 실행하세요."}
                </p>
            </section>
            ${polygonEditorHtml}
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
        if (lot.generatedMapExists && lot.generatedMapUrl) {
            return `
                <div class="lot-map-stage">
                    <img class="lot-map-bg" src="${lot.generatedMapUrl}" alt="${escapeHtml(lot.name)} 생성된 맵">
                    <div class="lot-map-overlay lot-map-overlay-empty">
                        <div class="lot-map-empty-copy">
                            <strong>기준선이 표시된 상태입니다.</strong>
                            <span>슬롯 레이아웃은 없지만 기준선 이미지는 확인할 수 있습니다.</span>
                        </div>
                    </div>
                </div>
            `;
        }
        return `
            <div class="lot-map-stage">
                <img class="lot-map-bg" src="${lot.sourceImageUrl}" alt="${escapeHtml(lot.name)} 원본 사진">
                <div class="lot-map-overlay lot-map-overlay-empty">
                    <div class="lot-map-empty-copy">
                        <strong>기준선이 아직 없습니다.</strong>
                        <span>사진은 업로드됐지만 기준선 정보가 없어서 오버레이를 표시할 수 없습니다.</span>
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

async function loadPolygonDraftForLot(lotId) {
    if (state.polygonDraftByLotId.has(lotId)) {
        return state.polygonDraftByLotId.get(lotId);
    }

    let draft = createEmptyPolygonDraft();
    try {
        const specText = await fetchText(`/api/parking-lots/${lotId}/map/polygon-spec`);
        draft = normalizePolygonSpec(JSON.parse(specText));
    } catch (error) {
        draft = createEmptyPolygonDraft();
    }

    state.polygonDraftByLotId.set(lotId, draft);
    return draft;
}

function createEmptyPolygonDraft() {
    return {
        mode: "polygon",
        activeAreaIndex: -1,
        areas: [],
        currentArea: [],
        areaEntrances: [],
        currentEntrance: [],
        obstacles: [],
        currentObstacle: [],
        metersPerPixel: 0.05,
    };
}

function normalizePolygonSpec(spec) {
    const draft = createEmptyPolygonDraft();
    if (!spec || typeof spec !== "object") {
        return draft;
    }

    draft.areas = normalizeAreaList(spec.areas ?? spec.parking_areas ?? spec.polygon ?? spec.parking_polygon ?? spec.area ?? []);
    draft.obstacles = normalizeObstacleList(spec.obstacles ?? spec.obstacle_polygons ?? spec.excluded_polygons ?? []);
    draft.areaEntrances = normalizeEntranceList(
        spec.entrances ?? spec.area_entrances ?? spec.areaEntrances ?? spec.entrance_segments ?? []
    );
    if (!draft.areaEntrances.length) {
        const legacyEntrance = normalizeEntrance(spec.entrance ?? spec.entrance_point ?? spec.entry);
        if (legacyEntrance.length >= 2) {
            draft.areaEntrances = draft.areas.map(() => legacyEntrance.slice(0, 2));
        }
    }
    draft.activeAreaIndex = draft.areas.length > 0 ? draft.areas.length - 1 : -1;
    draft.metersPerPixel = Number(spec.meters_per_pixel ?? spec.scale ?? spec.resolution_m_per_px ?? 0.05) || 0.05;
    return draft;
}

function normalizePointList(points) {
    if (!Array.isArray(points)) {
        return [];
    }

    return points.map((point) => normalizePoint(point)).filter(Boolean);
}

function normalizeObstacleList(obstacles) {
    if (!Array.isArray(obstacles)) {
        return [];
    }

    return obstacles.map((points) => normalizePointList(points)).filter((points) => points.length >= 3);
}

function normalizeAreaList(areas) {
    if (!Array.isArray(areas)) {
        return [];
    }

    const normalized = areas.map((points) => normalizePointList(points)).filter((points) => points.length >= 3);
    if (normalized.length > 0) {
        return normalized;
    }

    const fallback = normalizePointList(areas);
    return fallback.length >= 3 ? [fallback] : [];
}

function normalizePoint(point) {
    if (Array.isArray(point) && point.length >= 2) {
        return {
            x: Number(point[0]) || 0,
            y: Number(point[1]) || 0,
        };
    }

    if (point && typeof point === "object") {
        if (typeof point.x !== "undefined" && typeof point.y !== "undefined") {
            return {
                x: Number(point.x) || 0,
                y: Number(point.y) || 0,
            };
        }
        if (typeof point.cx !== "undefined" && typeof point.cy !== "undefined") {
            return {
                x: Number(point.cx) || 0,
                y: Number(point.cy) || 0,
            };
        }
    }

    return null;
}

function normalizeEntrance(value) {
    if (Array.isArray(value)) {
        if (value.length >= 2 && Array.isArray(value[0]) && Array.isArray(value[1])) {
            return value.slice(0, 2).map((point) => normalizePoint(point)).filter(Boolean);
        }

        const point = normalizePoint(value);
        return point ? [point] : [];
    }

    const point = normalizePoint(value);
    return point ? [point] : [];
}

function normalizeEntranceList(entrances) {
    if (!Array.isArray(entrances)) {
        return [];
    }

    return entrances.map((entry) => normalizeEntrance(entry).slice(0, 2));
}

function getAreaEntrance(draft, index) {
    const entrance = draft.areaEntrances?.[index] ?? [];
    return Array.isArray(entrance) ? entrance : [];
}

function currentAreaDisplayName(draft) {
    return draft.activeAreaIndex >= 0 ? `${draft.activeAreaIndex + 1}번 영역` : "선택 없음";
}

function renderPolygonEditor(lot, draft) {
    const areaCount = draft.areas.length + (draft.currentArea.length >= 3 ? 1 : 0);
    const obstacleCount = draft.obstacles.length + (draft.currentObstacle.length >= 3 ? 1 : 0);
    const activeAreaLabel = currentAreaDisplayName(draft);
    const entranceCount = draft.areaEntrances.filter((entry) => Array.isArray(entry) && entry.length >= 2).length;

    return `
        <section class="polygon-editor panel-subsection" data-polygon-editor="${lot.id}">
            <div class="polygon-editor-head">
                <div>
                    <div class="metric-label">자동 슬롯 생성</div>
                    <p>주차 가능 영역, 장애물, 각 폴리곤의 출입구 선분을 찍고 저장하면 폴리곤 스펙으로 전송됩니다.</p>
                </div>
                <div class="building-stats">
                    <span class="pill">주차영역 ${areaCount}개</span>
                    <span class="pill">장애물 ${obstacleCount}개</span>
                    <span class="pill">출입구 ${entranceCount}개</span>
                    <span class="pill">선택 ${activeAreaLabel}</span>
                </div>
            </div>
            <div class="polygon-toolbar">
                <button type="button" data-polygon-mode="polygon" data-polygon-lot="${lot.id}" class="${draft.mode === "polygon" ? "active" : ""}">주차영역</button>
                <button type="button" data-polygon-mode="obstacle" data-polygon-lot="${lot.id}" class="${draft.mode === "obstacle" ? "active" : ""}">장애물</button>
                <button type="button" data-polygon-mode="entrance" data-polygon-lot="${lot.id}" class="${draft.mode === "entrance" ? "active" : ""}">출입구 선분</button>
                <button type="button" data-polygon-undo data-polygon-lot="${lot.id}">한 단계 취소</button>
                <button type="button" data-polygon-finish data-polygon-lot="${lot.id}">영역 완료</button>
                <button type="button" data-polygon-clear data-polygon-lot="${lot.id}">초기화</button>
            </div>
            <div class="polygon-area-tabs">
                ${draft.areas.map((area, index) => {
                    const selected = draft.activeAreaIndex === index;
                    const hasEntrance = (draft.areaEntrances[index] ?? []).length >= 2;
                    return `<button type="button" class="polygon-area-tab ${selected ? "active" : ""}" data-polygon-area-index="${index}" data-polygon-lot="${lot.id}">
                        ${index + 1}번 영역 ${hasEntrance ? "· 출입구" : ""}
                    </button>`;
                }).join("")}
            </div>
            <div class="polygon-stage" data-polygon-stage="${lot.id}">
                <img class="polygon-stage-image" src="${lot.sourceImageUrl}" alt="${escapeHtml(lot.name)} 폴리곤 편집 대상">
                <svg class="polygon-stage-overlay" viewBox="0 0 ${LOT_MAP_WIDTH} ${LOT_MAP_HEIGHT}" preserveAspectRatio="none">
                    ${renderPolygonSvg(draft)}
                </svg>
            </div>
            <div class="polygon-editor-form">
                <label>
                    <span>meters_per_pixel (자동 추정)</span>
                    <input type="number" step="0.01" min="0.01" value="${draft.metersPerPixel}" data-polygon-scale="${lot.id}">
                </label>
                <div class="polygon-editor-actions">
                    <button type="button" data-polygon-save data-polygon-lot="${lot.id}">저장 후 자동 생성</button>
                    <button type="button" data-polygon-reload data-polygon-lot="${lot.id}">서버값 불러오기</button>
                </div>
            </div>
            <p class="lot-helper">
                주차영역은 클릭으로 점을 추가하고, 영역 완료를 누르면 새 독립 폴리곤이 시작됩니다. 장애물은 별도 모드에서 추가합니다. 영역 탭을 선택한 뒤 출입구 모드에서 두 점을 찍어 선분으로 지정합니다. 저장하면 자동 슬롯 생성이 바로 실행됩니다.
            </p>
        </section>
    `;
}

function renderPolygonSvg(draft) {
    const areas = draft.areas ?? [];
    const currentArea = draft.currentArea ?? [];
    const areaEntrances = draft.areaEntrances ?? [];
    const obstacles = draft.obstacles ?? [];
    const currentObstacle = draft.currentObstacle ?? [];
    const shapes = [];

    areas.forEach((area) => {
        if (area.length >= 2) {
            shapes.push(`<polyline class="polygon-line polygon-area-line" points="${area.map(pointToSvg).join(" ")}" />`);
        }
        if (area.length >= 3) {
            shapes.push(`<polygon class="polygon-fill polygon-area-fill" points="${area.map(pointToSvg).join(" ")}" />`);
        }
    });

    if (currentArea.length >= 2) {
        shapes.push(`<polyline class="polygon-line polygon-area-line polygon-current-line" points="${currentArea.map(pointToSvg).join(" ")}" />`);
    }
    if (currentArea.length >= 3) {
        shapes.push(`<polygon class="polygon-fill polygon-area-fill polygon-current-fill" points="${currentArea.map(pointToSvg).join(" ")}" />`);
    }

    obstacles.forEach((obstacle) => {
        if (obstacle.length >= 2) {
            shapes.push(`<polyline class="polygon-line polygon-obstacle-line" points="${obstacle.map(pointToSvg).join(" ")}" />`);
        }
        if (obstacle.length >= 3) {
            shapes.push(`<polygon class="polygon-fill polygon-obstacle-fill" points="${obstacle.map(pointToSvg).join(" ")}" />`);
        }
    });

    if (currentObstacle.length >= 2) {
        shapes.push(`<polyline class="polygon-line polygon-obstacle-line polygon-current-line" points="${currentObstacle.map(pointToSvg).join(" ")}" />`);
    }
    if (currentObstacle.length >= 3) {
        shapes.push(`<polygon class="polygon-fill polygon-obstacle-fill polygon-current-fill" points="${currentObstacle.map(pointToSvg).join(" ")}" />`);
    }

    areaEntrances.forEach((entrance, index) => {
        if (Array.isArray(entrance) && entrance.length >= 2) {
            shapes.push(`<line class="polygon-entrance-line" data-area-entrance-index="${index}" x1="${entrance[0].x}" y1="${entrance[0].y}" x2="${entrance[1].x}" y2="${entrance[1].y}" />`);
        }
    });

    areas.flat().forEach((point) => {
        shapes.push(`<circle class="polygon-point" cx="${point.x}" cy="${point.y}" r="4" />`);
    });
    currentArea.forEach((point) => {
        shapes.push(`<circle class="polygon-point" cx="${point.x}" cy="${point.y}" r="4" />`);
    });
    obstacles.flat().forEach((point) => {
        shapes.push(`<circle class="polygon-point" cx="${point.x}" cy="${point.y}" r="4" />`);
    });
    currentObstacle.forEach((point) => {
        shapes.push(`<circle class="polygon-point" cx="${point.x}" cy="${point.y}" r="4" />`);
    });
    areaEntrances.flat().forEach((point) => {
        shapes.push(`<circle class="polygon-entrance-point" cx="${point.x}" cy="${point.y}" r="4" />`);
    });

    if (draft.mode === "entrance") {
        shapes.push(`<text class="polygon-hint" x="16" y="24">출입구 모드</text>`);
    } else if (draft.mode === "obstacle") {
        shapes.push(`<text class="polygon-hint" x="16" y="24">장애물 모드</text>`);
    } else {
        shapes.push(`<text class="polygon-hint" x="16" y="24">주차영역 모드</text>`);
    }

    return shapes.join("");
}

function pointToSvg(point) {
    return `${point.x},${point.y}`;
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
        const buildButton = card.querySelector("[data-lot-build-btn]");
        const refreshButton = card.querySelector("[data-lot-refresh-btn]");
        const saveLocationButton = card.querySelector(`[data-save-location-btn="${lot.id}"]`);
        const releaseLocationButton = card.querySelector(`[data-release-location-btn="${lot.id}"]`);
        const createAlertButton = card.querySelector(`[data-create-alert-btn="${lot.id}"]`);
        const alertThresholdInput = card.querySelector(`[data-alert-threshold="${lot.id}"]`);

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

                    const response = await fetch(`/api/parking-lots/${lot.id}/map/upload`, {
                        method: "POST",
                        body: formData,
                    });

                    if (!response.ok) {
                        throw new Error(`업로드 실패 (${response.status})`);
                    }

                    const result = await response.json();
                    elements.updateBadge.textContent = result.statusMessage ?? "사진 업로드가 완료되었습니다.";
                    await renderSelectedBuilding(state.selectedBuildingId);
                } catch (error) {
                    alert(error.message);
                }
            });
        }

        if (buildButton) {
            buildButton.addEventListener("click", async () => {
                try {
                    const response = await fetch(`/api/parking-lots/${lot.id}/map/build`, {
                        method: "POST",
                    });

                    if (!response.ok) {
                        throw new Error(`맵 제작 실행 실패 (${response.status})`);
                    }

                    const result = await response.json();
                    elements.updateBadge.textContent = result.statusMessage ?? "맵 빌더 실행됨";
                    await renderSelectedBuilding(state.selectedBuildingId);
                } catch (error) {
                    alert(error.message);
                }
            });
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
}

function bindPolygonEditors(lots) {
    lots.forEach((lot) => {
        const card = document.querySelector(`[data-parking-lot-card="${lot.id}"]`);
        if (!card) {
            return;
        }

        const draft = state.polygonDraftByLotId.get(lot.id) ?? createEmptyPolygonDraft();
        state.polygonDraftByLotId.set(lot.id, draft);

        const stage = card.querySelector(`[data-polygon-stage="${lot.id}"]`);
        const saveButton = card.querySelector(`[data-polygon-save][data-polygon-lot="${lot.id}"]`);
        const reloadButton = card.querySelector(`[data-polygon-reload][data-polygon-lot="${lot.id}"]`);
        const clearButton = card.querySelector(`[data-polygon-clear][data-polygon-lot="${lot.id}"]`);
        const undoButton = card.querySelector(`[data-polygon-undo][data-polygon-lot="${lot.id}"]`);
        const finishButton = card.querySelector(`[data-polygon-finish][data-polygon-lot="${lot.id}"]`);
        const scaleInput = card.querySelector(`[data-polygon-scale="${lot.id}"]`);
        const modeButtons = card.querySelectorAll(`[data-polygon-mode][data-polygon-lot="${lot.id}"]`);

        const refreshCard = async () => {
            await renderSelectedBuilding(state.selectedBuildingId);
        };

        const setMode = (mode) => {
            draft.mode = mode;
            refreshCard();
        };

        const updateDraftFromClick = (event) => {
            if (!stage) {
                return;
            }
            const rect = stage.getBoundingClientRect();
            const x = ((event.clientX - rect.left) / rect.width) * LOT_MAP_WIDTH;
            const y = ((event.clientY - rect.top) / rect.height) * LOT_MAP_HEIGHT;
            const point = {
                x: clamp(x, 0, LOT_MAP_WIDTH),
                y: clamp(y, 0, LOT_MAP_HEIGHT),
            };

            if (draft.mode === "entrance") {
                if (draft.activeAreaIndex < 0 && draft.areas.length) {
                    draft.activeAreaIndex = draft.areas.length - 1;
                }
                if (draft.activeAreaIndex >= 0) {
                    if (draft.currentEntrance.length >= 2) {
                        draft.currentEntrance = [];
                    }
                    draft.currentEntrance.push(point);
                    draft.areaEntrances[draft.activeAreaIndex] = [...draft.currentEntrance].slice(0, 2);
                    if (draft.currentEntrance.length >= 2) {
                        draft.mode = "polygon";
                    }
                }
                refreshCard();
                return;
            }

            if (draft.mode === "obstacle") {
                draft.currentObstacle.push(point);
                refreshCard();
                return;
            }

            draft.currentArea.push(point);
            refreshCard();
        };

        if (stage) {
            stage.addEventListener("click", updateDraftFromClick);
        }

        modeButtons.forEach((button) => {
            button.addEventListener("click", () => {
                setMode(button.dataset.polygonMode ?? "polygon");
            });
        });

        card.querySelectorAll("[data-polygon-area-index]").forEach((button) => {
            button.addEventListener("click", () => {
                const index = Number(button.dataset.polygonAreaIndex);
                if (Number.isNaN(index)) {
                    return;
                }
                draft.activeAreaIndex = index;
                draft.currentEntrance = [...(draft.areaEntrances[index] ?? [])];
                draft.mode = "entrance";
                refreshCard();
            });
        });

        if (undoButton) {
            undoButton.addEventListener("click", () => {
                if (draft.mode === "obstacle" && draft.currentObstacle.length) {
                    draft.currentObstacle.pop();
                } else if (draft.mode === "polygon" && draft.currentArea.length) {
                    draft.currentArea.pop();
                } else if (draft.mode === "entrance") {
                    draft.currentEntrance.pop();
                } else if (draft.areas.length) {
                    draft.areas.pop();
                    draft.areaEntrances.pop();
                    draft.activeAreaIndex = draft.areas.length - 1;
                } else if (draft.obstacles.length) {
                    draft.obstacles.pop();
                }
                refreshCard();
            });
        }

        if (finishButton) {
            finishButton.addEventListener("click", () => {
                if (draft.mode === "polygon" && draft.currentArea.length >= 3) {
                    draft.areas.push([...draft.currentArea]);
                    draft.areaEntrances.push([]);
                    draft.currentArea = [];
                    draft.currentEntrance = [];
                    draft.activeAreaIndex = draft.areas.length - 1;
                } else if (draft.mode === "obstacle" && draft.currentObstacle.length >= 3) {
                    draft.obstacles.push([...draft.currentObstacle]);
                    draft.currentObstacle = [];
                }
                refreshCard();
            });
        }

        if (clearButton) {
            clearButton.addEventListener("click", () => {
                state.polygonDraftByLotId.set(lot.id, createEmptyPolygonDraft());
                refreshCard();
            });
        }

        if (scaleInput) {
            scaleInput.addEventListener("change", () => {
                draft.metersPerPixel = Number(scaleInput.value) || 0.05;
            });
        }

        if (saveButton) {
            saveButton.addEventListener("click", async () => {
                try {
                    const finalAreas = [...draft.areas];
                    if (draft.currentArea.length >= 3) {
                        finalAreas.push([...draft.currentArea]);
                    }
                    if (!finalAreas.length) {
                        alert("주차 가능 영역은 최소 하나 이상 필요합니다.");
                        return;
                    }
                    const finalEntrances = finalAreas.map((_, index) => {
                        const savedEntrance = draft.areaEntrances[index] ?? [];
                        if (Array.isArray(savedEntrance) && savedEntrance.length >= 2) {
                            return savedEntrance.slice(0, 2);
                        }
                        if (index === draft.activeAreaIndex && draft.currentEntrance.length >= 2) {
                            return draft.currentEntrance.slice(0, 2);
                        }
                        return null;
                    });
                    const payload = {
                        meters_per_pixel: Number(draft.metersPerPixel) || 0.05,
                        areas: finalAreas.map((area) => area.map((point) => [Math.round(point.x), Math.round(point.y)])),
                        obstacles: draft.obstacles.map((obstacle) => obstacle.map((point) => [Math.round(point.x), Math.round(point.y)])),
                        entrances: finalEntrances.map((entry) => entry ? entry.map((point) => [Math.round(point.x), Math.round(point.y)]) : null),
                        angles: Array.from({ length: 36 }, (_, index) => index * 5),
                    };

                    await apiRequest(`/api/parking-lots/${lot.id}/map/polygon-spec`, {
                        method: "POST",
                        body: JSON.stringify(payload),
                    });
                    await apiRequest(`/api/parking-lots/${lot.id}/map/build`, {
                        method: "POST",
                    });
                    state.polygonDraftByLotId.delete(lot.id);
                    elements.updateBadge.textContent = "폴리곤 스펙 저장 후 자동 생성 완료";
                    await renderSelectedBuilding(state.selectedBuildingId);
                } catch (error) {
                    alert(error.message);
                }
            });
        }

        if (reloadButton) {
            reloadButton.addEventListener("click", async () => {
                state.polygonDraftByLotId.delete(lot.id);
                try {
                    await loadPolygonDraftForLot(lot.id);
                    await renderSelectedBuilding(state.selectedBuildingId);
                } catch (error) {
                    alert(error.message);
                }
            });
        }
    });
}

async function renderMapIfPossible() {
    const clientId = state.config?.naverMapClientId?.trim();
    if (!clientId) {
        elements.mapFallback.classList.remove("hidden");
        elements.mapFallback.innerHTML = "네이버 지도 클라이언트 ID가 설정되지 않았습니다.<br>건물 선택과 상태 확인은 계속 사용할 수 있습니다.";
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
        script.src = `https://oapi.map.naver.com/openapi/v3/maps.js?ncpKeyId=${encodeURIComponent(clientId)}&language=ko`;
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

    elements.mapFallback.classList.add("hidden");

    if (state.selectedBuildingId) {
        focusMarker(state.selectedBuildingId);
    }
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

async function fetchText(url, options = {}) {
    const response = await fetch(url, options);
    if (!response.ok) {
        throw new Error(`${url} 요청 실패 (${response.status})`);
    }
    return response.text();
}

function clamp(value, min, max) {
    return Math.min(max, Math.max(min, value));
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
