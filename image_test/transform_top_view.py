import cv2
import numpy as np
import matplotlib.pyplot as plt

file_name = 'image.png'
save_name = 'output_top_view.png'

# 전역 변수 설정
src_points = []
img_display = None

def mouse_callback(event, x, y, flags, param):
    global src_points, img_display

    # 마우스 왼쪽 버튼 클릭 시
    if event == cv2.EVENT_LBUTTONDOWN:
        src_points.append([x, y])
        print(f"선택한 좌표 {len(src_points)}: ({x}, {y})")

        # 클릭한 위치에 빨간 점 찍어서 표시
        cv2.circle(img_display, (x, y), 5, (0, 0, 255), -1)
        cv2.imshow('Original Image', img_display)

        # 4개의 점이 다 선택되면 변환 실행
        if len(src_points) == 4:
            transform_image()

def transform_image():
    global src_points
    
    # 1. 입력 좌표 (float32 형변환 필수)
    pts1 = np.float32(src_points)

    # 2. 출력 좌표 설정 (변환될 이미지의 크기)
    # 실제 주차장 칸의 비율(가로:세로)을 고려해서 숫자를 넣으면 더 리얼하다고 함.
    # width:height = 2:3 비율로 설정 -> 400x600
    width, height = 400, 600
    
    # 순서대로 좌상->우상->우하->좌하 위치로 매핑
    pts2 = np.float32([
        [0, 0], 
        [width, 0], 
        [width, height], 
        [0, height]
    ])

    # 3. 변환 행렬 계산 (핵심)
    matrix = cv2.getPerspectiveTransform(pts1, pts2)

    # 4. 이미지 변환 (Warp)
    # 원본 이미지(img)를 사용해서 변환 (점 찍힌 이미지 x)
    result = cv2.warpPerspective(img, matrix, (width, height))

    cv2.imwrite(save_name, result)
    print(f"\n[저장 완료] 이미지가 '{save_name}'으로 저장되었습니다!")

    # 결과 출력
    cv2.imshow('Top View Result', result)
    print("변환 완료! 결과 창을 확인하세요.")

# --- 메인 실행 코드 ---
img = cv2.imread(file_name)

if img is None:
    print("이미지 파일을 찾을 수 없습니다. 파일 경로를 확인해주세요.")
else:
    # 화면에 보여줄 복사본 생성
    img_display = img.copy()

    cv2.imshow('Original Image', img_display)
    cv2.setMouseCallback('Original Image', mouse_callback)

    print("이미지 위에서 [좌상 -> 우상 -> 우하 -> 좌하] 순서로 4번 클릭하세요.")
    
    cv2.waitKey(0)
    cv2.destroyAllWindows()