package com.smartparking.server.service;

import com.smartparking.server.dto.ParkingLotMapResponse;
import com.smartparking.server.entity.ParkingLot;
import com.smartparking.server.repository.ParkingLotRepository;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
@RequiredArgsConstructor
public class ParkingLotMapService {

    private final ParkingLotRepository parkingLotRepository;

    @Transactional(readOnly = true)
    public ParkingLotMapResponse getMap(Long parkingLotId) {
        ParkingLot parkingLot = getParkingLot(parkingLotId);
        return toResponse(parkingLot, "주차장 맵 상태를 불러왔습니다.");
    }

    public ParkingLotMapResponse uploadSourceImage(Long parkingLotId, MultipartFile file) {
        ParkingLot parkingLot = getParkingLot(parkingLotId);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload file is required");
        }

        Path sourceImagePath = sourceImagePath(parkingLot);
        try {
            Files.createDirectories(sourceImagePath.getParent());
            BufferedImage image = ImageIO.read(file.getInputStream());
            if (image == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported image format");
            }
            ImageIO.write(image, "png", sourceImagePath.toFile());
            Files.deleteIfExists(generatedMapPath(parkingLot));
            Files.deleteIfExists(slotLayoutPath(parkingLot));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store uploaded image", e);
        }

        return toResponse(parkingLot, "사진 업로드가 완료되었습니다.");
    }

    public ParkingLotMapResponse launchMapBuilder(Long parkingLotId) {
        ParkingLot parkingLot = getParkingLot(parkingLotId);
        Path sourceImagePath = sourceImagePath(parkingLot);
        if (!Files.exists(sourceImagePath)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload an image before launching the map builder");
        }

        Path scriptPath = resolveMapBuilderScript();
        Path pythonPath = resolvePythonExecutable();

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    pythonPath.toString(),
                    scriptPath.toString(),
                    parkingLot.getPartitionKey());
            processBuilder.directory(scriptPath.getParent().toFile());
            processBuilder.inheritIO();
            processBuilder.start();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to launch map builder", e);
        }

        return toResponse(parkingLot, "맵 빌더를 실행했습니다. 로컬 창에서 저장을 완료하세요.");
    }

    @Transactional(readOnly = true)
    public byte[] readSourceImage(Long parkingLotId) {
        ParkingLot parkingLot = getParkingLot(parkingLotId);
        Path sourceImagePath = sourceImagePath(parkingLot);
        if (!Files.exists(sourceImagePath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source image not found");
        }
        return readBytes(sourceImagePath);
    }

    @Transactional(readOnly = true)
    public byte[] readGeneratedMapImage(Long parkingLotId) {
        ParkingLot parkingLot = getParkingLot(parkingLotId);
        Path generatedMapPath = generatedMapPath(parkingLot);
        if (!Files.exists(generatedMapPath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Generated map image not found");
        }
        return readBytes(generatedMapPath);
    }

    @Transactional(readOnly = true)
    public String readSlotLayoutJson(Long parkingLotId) {
        ParkingLot parkingLot = getParkingLot(parkingLotId);
        Path slotLayoutPath = slotLayoutPath(parkingLot);
        if (!Files.exists(slotLayoutPath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Slot layout not found");
        }
        try {
            return Files.readString(slotLayoutPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read slot layout", e);
        }
    }

    @Transactional(readOnly = true)
    public ParkingLotMapResponse snapshot(ParkingLot parkingLot) {
        return toResponse(parkingLot, "주차장 맵 상태를 불러왔습니다.");
    }

    private ParkingLot getParkingLot(Long parkingLotId) {
        return parkingLotRepository.findById(parkingLotId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Parking lot not found: " + parkingLotId));
    }

    private ParkingLotMapResponse toResponse(ParkingLot parkingLot, String message) {
        boolean sourceImageExists = Files.exists(sourceImagePath(parkingLot));
        boolean generatedMapExists = Files.exists(generatedMapPath(parkingLot)) && Files.exists(slotLayoutPath(parkingLot));
        String slotLayoutJson = generatedMapExists ? readSlotLayoutJsonSafe(parkingLot) : null;
        return new ParkingLotMapResponse(
                parkingLot.getId(),
                parkingLot.getName(),
                parkingLot.getPartitionKey(),
                sourceImageExists,
                generatedMapExists,
                sourceImageExists ? "/api/parking-lots/" + parkingLot.getId() + "/map/source-image" : null,
                generatedMapExists ? "/api/parking-lots/" + parkingLot.getId() + "/map/generated-image" : null,
                slotLayoutJson,
                message);
    }

    private String readSlotLayoutJsonSafe(ParkingLot parkingLot) {
        try {
            return Files.readString(slotLayoutPath(parkingLot), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read slot layout", e);
        }
    }

    private byte[] readBytes(Path path) {
        try {
            return Files.readAllBytes(path);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read image", e);
        }
    }

    private Path sourceImagePath(ParkingLot parkingLot) {
        return resolveVideoTestRoot().resolve("images").resolve(parkingLot.getPartitionKey() + "_image.png");
    }

    private Path generatedMapPath(ParkingLot parkingLot) {
        return resolveVideoTestRoot().resolve("map").resolve(parkingLot.getPartitionKey() + "_map.png");
    }

    private Path slotLayoutPath(ParkingLot parkingLot) {
        return resolveVideoTestRoot().resolve("map").resolve(parkingLot.getPartitionKey() + "_slots.json");
    }

    private Path resolveMapBuilderScript() {
        return resolveExistingPath(
                Paths.get("fastapi", "map_builder", "map_builder_gui0.py"),
                Paths.get("..", "fastapi", "map_builder", "map_builder_gui0.py"),
                Paths.get("..", "..", "fastapi", "map_builder", "map_builder_gui0.py"));
    }

    private Path resolvePythonExecutable() {
        Path venvPython = resolveExistingPath(
                Paths.get("fastapi", "video_test", "venv", "bin", "python"),
                Paths.get("..", "fastapi", "video_test", "venv", "bin", "python"),
                Paths.get("..", "..", "fastapi", "video_test", "venv", "bin", "python"));
        if (Files.exists(venvPython)) {
            return venvPython;
        }
        return Paths.get("python3");
    }

    private Path resolveVideoTestRoot() {
        return resolveExistingPath(
                Paths.get("fastapi", "video_test"),
                Paths.get("..", "fastapi", "video_test"),
                Paths.get("..", "..", "fastapi", "video_test"));
    }

    private Path resolveExistingPath(Path... candidates) {
        for (Path candidate : candidates) {
            Path absolute = candidate.toAbsolutePath().normalize();
            if (Files.exists(absolute)) {
                return absolute;
            }
        }
        return candidates[0].toAbsolutePath().normalize();
    }
}
