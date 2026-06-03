import cv2
import numpy as np

# ==========================================
# 1. 작업할 이미지 파일명 (Top-view로 변환된 이미지)
img_path = 'output_top_view.png' 
# ==========================================

drawing = False # 현재 드래그 중인지 확인
ix, iy = -1, -1 # 드래그 시작 지점
rectangles = [] # 그려진 사각형들을 저장할 리스트

def mouse_callback(event, x, y, flags, param):
    global ix, iy, drawing, img_display

    # 1. 마우스 클릭 (드래그 시작)
    if event == cv2.EVENT_LBUTTONDOWN:
        drawing = True
        ix, iy = x, y

    # 2. 마우스 이동 (드래그 중 - 미리보기 상자 그리기)
    elif event == cv2.EVENT_MOUSEMOVE:
        if drawing:
            img_temp = img_display.copy()
            cv2.rectangle(img_temp, (ix, iy), (x, y), (0, 255, 0), 2)
            cv2.imshow('Mask Maker', img_temp)

    # 3. 마우스 떼기 (드래그 끝 - 사각형 확정)
    elif event == cv2.EVENT_LBUTTONUP:
        drawing = False
        # 사각형 정보 저장 (x1, y1, x2, y2)
        rectangles.append((ix, iy, x, y))
        redraw_image() # 화면 갱신

def redraw_image():
    """저장된 모든 사각형을 이미지 위에 다시 그립니다."""
    global img_display
    img_display = img.copy()
    for (x1, y1, x2, y2) in rectangles:
        # 빨간색, 두께 2로 그리기
        cv2.rectangle(img_display, (x1, y1), (x2, y2), (0, 0, 255), 2)
        # 번호 매기기 (옵션)
        cv2.putText(img_display, "P", (min(x1, x2), min(y1, y2)-5), 
                    cv2.FONT_HERSHEY_SIMPLEX, 0.5, (0, 0, 255), 1)
    cv2.imshow('Mask Maker', img_display)

def save_result():
    """마스크 이미지와 좌표 텍스트를 파일로 저장합니다."""
    # 1. 마스크 이미지 생성 (검은 배경)
    mask = np.zeros_like(img)
    
    # 2. 흰색 사각형 채우기 (-1은 내부 채움 의미)
    for (x1, y1, x2, y2) in rectangles:
        cv2.rectangle(mask, (x1, y1), (x2, y2), (255, 255, 255), -1)
    
    # 3. 파일 저장
    cv2.imwrite('parking_mask.png', mask)
    
    # 좌표도 따로 텍스트로 저장
    with open('parking_coords.txt', 'w') as f:
        for rect in rectangles:
            f.write(f"{rect}\n")
            
    print(f"총 {len(rectangles)}개의 주차 구역이 저장되었습니다.")
    print("- 이미지: parking_mask.png")
    print("- 좌표: parking_coords.txt")

# --- 메인 실행 ---
img = cv2.imread(img_path)

if img is None:
    print(f"이미지 파일({img_path})을 찾을 수 없습니다.")
else:
    img_display = img.copy()
    cv2.imshow('Mask Maker', img_display)
    cv2.setMouseCallback('Mask Maker', mouse_callback)

    print("=== 사용 방법 ===")
    print("1. 마우스 드래그: 주차 구역 그리기")
    print("2. 'z' 키: 방금 그린 사각형 취소 (Undo)")
    print("3. 's' 키: 저장하고 종료 (Save)")
    print("4. 'q' 키: 저장 없이 종료 (Quit)")

    while True:
        key = cv2.waitKey(1) & 0xFF

        if key == ord('s'): # Save
            save_result()
            break
        elif key == ord('z'): # Undo
            if rectangles:
                rectangles.pop() # 리스트에서 마지막 사각형 제거
                redraw_image()   # 다시 그리기
                print("실행 취소됨.")
        elif key == ord('q'): # Quit
            print("저장하지 않고 종료합니다.")
            break

    cv2.destroyAllWindows()