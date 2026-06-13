package com.smartparking.server.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup;

import com.smartparking.server.entity.Campus;
import com.smartparking.server.repository.CampusRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.context.WebApplicationContext;

@SpringBootTest
@TestPropertySource(properties = {
        "smartparking.asset-root=${java.io.tmpdir}/sp-test-assets",
        "spring.datasource.url=jdbc:h2:mem:sptestctl;DB_CLOSE_DELAY=-1"
})
class BuildingControllerTest {

    private MockMvc mockMvc;

    @Autowired
    private WebApplicationContext webApplicationContext;

    @Autowired
    private CampusRepository campusRepository;

    @BeforeEach
    void setUp() {
        mockMvc = webAppContextSetup(webApplicationContext)
                .apply(SecurityMockMvcConfigurers.springSecurity())
                .build();
        if (campusRepository.count() == 0) {
            Campus campus = new Campus();
            campus.setName("c");
            campus.setCenterLat(37.0);
            campus.setCenterLng(127.0);
            campus.setDefaultZoom(17);
            campusRepository.save(campus);
        }
    }

    @Test
    @WithMockUser
    void createBuildingReturnsOkWithMapKey() throws Exception {
        mockMvc.perform(post("/api/buildings")
                        .contentType("application/json")
                        .content("{\"name\":\"테스트동\",\"lat\":37.45,\"lng\":127.13}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.mapKey").exists())
                .andExpect(jsonPath("$.name").value("테스트동"));
    }

    @Test
    @WithMockUser
    void createBuildingWithMissingNameReturnsBadRequest() throws Exception {
        mockMvc.perform(post("/api/buildings")
                        .contentType("application/json")
                        .content("{\"lat\":37.45,\"lng\":127.13}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void createBuildingWithoutAuthIsUnauthorized() throws Exception {
        mockMvc.perform(post("/api/buildings")
                        .contentType("application/json")
                        .content("{\"name\":\"x\",\"lat\":37.0,\"lng\":127.0}"))
                .andExpect(status().isUnauthorized());
    }
}
