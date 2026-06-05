package com.smartparking.server.service;

import com.smartparking.server.dto.BuildingMapResponse;
import com.smartparking.server.entity.Building;
import com.smartparking.server.repository.BuildingRepository;
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
public class BuildingMapService {

    private final BuildingRepository buildingRepository;

    @Transactional(readOnly = true)
    public BuildingMapResponse getMap(Long buildingId) {
        Building building = getBuilding(buildingId);
        return toResponse(building, "맵 상태를 불러왔습니다.");
    }

    public BuildingMapResponse uploadSourceImage(Long buildingId, MultipartFile file) {
        Building building = getBuilding(buildingId);
        if (file == null || file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload file is required");
        }

        Path sourceImagePath = sourceImagePath(building);
        try {
            Files.createDirectories(sourceImagePath.getParent());
            BufferedImage image = ImageIO.read(file.getInputStream());
            if (image == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported image format");
            }
            ImageIO.write(image, "png", sourceImagePath.toFile());
            Files.deleteIfExists(generatedMapPath(building));
            Files.deleteIfExists(slotLayoutPath(building));
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to store uploaded image", e);
        }

        return toResponse(building, "사진 업로드가 완료되었습니다.");
    }

    public BuildingMapResponse launchMapBuilder(Long buildingId) {
        Building building = getBuilding(buildingId);
        Path sourceImagePath = sourceImagePath(building);
        if (!Files.exists(sourceImagePath)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Upload an image before launching the map builder");
        }

        Path scriptPath = resolveMapBuilderScript();
        Path pythonPath = resolvePythonExecutable();

        try {
            ProcessBuilder processBuilder = new ProcessBuilder(
                    pythonPath.toString(),
                    scriptPath.toString(),
                    building.getMapKey());
            processBuilder.directory(scriptPath.getParent().toFile());
            processBuilder.inheritIO();
            processBuilder.start();
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to launch map builder", e);
        }

        return toResponse(building, "맵 빌더를 실행했습니다. 로컬 창에서 저장을 완료하세요.");
    }

    @Transactional(readOnly = true)
    public byte[] readSourceImage(Long buildingId) {
        Building building = getBuilding(buildingId);
        Path sourceImagePath = sourceImagePath(building);
        if (!Files.exists(sourceImagePath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Source image not found");
        }
        return readBytes(sourceImagePath);
    }

    @Transactional(readOnly = true)
    public byte[] readGeneratedMapImage(Long buildingId) {
        Building building = getBuilding(buildingId);
        Path generatedMapPath = generatedMapPath(building);
        if (!Files.exists(generatedMapPath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Generated map image not found");
        }
        return readBytes(generatedMapPath);
    }

    @Transactional(readOnly = true)
    public String readSlotLayoutJson(Long buildingId) {
        Building building = getBuilding(buildingId);
        Path slotLayoutPath = slotLayoutPath(building);
        if (!Files.exists(slotLayoutPath)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Slot layout not found");
        }
        try {
            return Files.readString(slotLayoutPath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to read slot layout", e);
        }
    }

    private Building getBuilding(Long buildingId) {
        return buildingRepository.findById(buildingId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Building not found: " + buildingId));
    }

    private BuildingMapResponse toResponse(Building building, String message) {
        boolean sourceImageExists = Files.exists(sourceImagePath(building));
        boolean generatedMapExists = Files.exists(generatedMapPath(building)) && Files.exists(slotLayoutPath(building));
        String slotLayoutJson = generatedMapExists ? readSlotLayoutJsonSafe(building) : null;
        return new BuildingMapResponse(
                building.getId(),
                building.getName(),
                building.getMapKey(),
                sourceImageExists,
                generatedMapExists,
                sourceImageExists ? "/api/campus/buildings/" + building.getId() + "/map/source-image" : null,
                generatedMapExists ? "/api/campus/buildings/" + building.getId() + "/map/generated-image" : null,
                slotLayoutJson,
                message);
    }

    private String readSlotLayoutJsonSafe(Building building) {
        try {
            return Files.readString(slotLayoutPath(building), StandardCharsets.UTF_8);
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

    private Path sourceImagePath(Building building) {
        return resolveVideoTestRoot().resolve("images").resolve(building.getMapKey() + "_image.png");
    }

    private Path generatedMapPath(Building building) {
        return resolveVideoTestRoot().resolve("map").resolve(building.getMapKey() + "_map.png");
    }

    private Path slotLayoutPath(Building building) {
        return resolveVideoTestRoot().resolve("map").resolve(building.getMapKey() + "_slots.json");
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
