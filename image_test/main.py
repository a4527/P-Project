import cv2
import numpy as np
import pickle
from skimage.transform import resize

# ==========================================
# 파일 경로 설정
img_path = 'output_top_view.png'            # 테스트할 원본 이미지
mask_path = 'parking_mask.png'    # 아까 만든 마스크 이미지
# model_path = 'model.p'            # 깃허브에서 받은 모델 파일 (깃허브에서 model.p 제공하지 않음)
# ==========================================

# 1. 모델 로드 (없으면 에러 발생)
# try:
#     with open(model_path, "rb") as f:
#         MODEL = pickle.load(f)
#     print("모델 로드 성공!")
# except FileNotFoundError:
#     print(f"오류: {model_path} 파일을 찾을 수 없습니다.")
#     print("깃허브에서 model.p 파일을 다운로드하거나, 아래 '임시 판독 함수'를 사용하세요.")
#     exit()

# 빈자리/차있음 상수
EMPTY = True
NOT_EMPTY = False

def get_parking_spots_bboxes(connected_components):
    (totalLabels, label_ids, values, centroid) = connected_components
    slots = []
    coef = 1
    for i in range(1, totalLabels):
        # 좌표 추출 (x, y, w, h)
        x1 = int(values[i, cv2.CC_STAT_LEFT] * coef)
        y1 = int(values[i, cv2.CC_STAT_TOP] * coef)
        w = int(values[i, cv2.CC_STAT_WIDTH] * coef)
        h = int(values[i, cv2.CC_STAT_HEIGHT] * coef)
        slots.append([x1, y1, w, h])
    return slots

# def empty_or_not(spot_bgr):
#     flat_data = []
#     # 모델이 학습된 크기(15x15)로 리사이즈
#     img_resized = resize(spot_bgr, (15, 15, 3))
#     flat_data.append(img_resized.flatten())
#     flat_data = np.array(flat_data)

#     y_output = MODEL.predict(flat_data)

#     if y_output == 0:
#         return EMPTY
#     else:
#         return NOT_EMPTY


# 머신러닝 모델 없이 밝기로만 판단하는 임시 함수
def empty_or_not(spot_bgr):
    # 이미지 평균 밝기 계산
    avg_brightness = np.mean(spot_bgr)
    
    # 기준값 (Threshold) - 상황에 따라 조절 필요 (예: 100)
    # 바닥이 어둡고 차가 밝다면: 밝기가 높으면(>100) 차 있음(NOT_EMPTY)
    if avg_brightness > 50: 
        return NOT_EMPTY
    else:
        return EMPTY

# --- 메인 로직 시작 ---

# 1. 이미지와 마스크 읽기
image = cv2.imread(img_path)
mask = cv2.imread(mask_path, 0) # 흑백 모드로 읽기
update_mask = mask.copy()
# update_mask를 컬러로 변환 (초기에는 흑백이므로 빨간색, 초록색으로 칠하려면 컬러여야 함)
update_mask = cv2.cvtColor(update_mask, cv2.COLOR_GRAY2BGR)


if image is None or mask is None:
    print("이미지나 마스크 파일을 찾을 수 없습니다.")
    exit()

# 2. 마스크에서 주차 구역 위치(Bbox) 뽑아내기
# connectedComponentsWithStats: 흰색 덩어리들을 찾아줌.
connected_components = cv2.connectedComponentsWithStats(mask, 4, cv2.CV_32S)
spots = get_parking_spots_bboxes(connected_components)

print(f"총 {len(spots)}개의 주차 구역을 감지했습니다.")

# 3. 각 구역을 잘라내서(Crop) 분류기(Classifier)에 넣기
spots_status = []
for spot in spots:
    x1, y1, w, h = spot
    
    # 이미지 잘라내기
    spot_crop = image[y1:y1 + h, x1:x1 + w, :]
    
    # 모델로 판독 (빈 자리인지/차 있는 자리인지)
    status = empty_or_not(spot_crop)
    spots_status.append(status)

# 4. 결과 그리기
for index, spot in enumerate(spots):
    x1, y1, w, h = spot
    if spots_status[index] == EMPTY:
        # 빈 자리는 초록색 박스
        cv2.rectangle(image, (x1, y1), (x1 + w, y1 + h), (0, 255, 0), 2)
        # parking_mask에서 빈 자리는 초록색으로 박스 채우기
        cv2.rectangle(update_mask, (x1 + 1, y1 + 1), (x1 + w - 2, y1 + h - 2), (0, 255, 0), -1)
    else:
        # 차 있는 자리는 빨간색 박스
        cv2.rectangle(image, (x1, y1), (x1 + w, y1 + h), (0, 0, 255), 2)
        # parking_mask에서 차 있는 자리는 빨간색으로 박스 채우기
        cv2.rectangle(update_mask, (x1 + 1, y1 + 1), (x1 + w - 2, y1 + h - 2), (0, 0, 255), -1)

# 5. 텍스트 표시
cv2.rectangle(image, (0, 0), (400, 60), (0, 0, 0), -1)
cv2.putText(image, f'Available: {sum(spots_status)} / {len(spots)}', (20, 40),
            cv2.FONT_HERSHEY_SIMPLEX, 1, (255, 255, 255), 2)

# 결과 보여주기
cv2.imshow('Image Test Result', image)
cv2.imwrite('result_with_no_model.png', image) 
cv2.imwrite('updated_parking_mask.png', update_mask)
cv2.waitKey(0)
cv2.destroyAllWindows()