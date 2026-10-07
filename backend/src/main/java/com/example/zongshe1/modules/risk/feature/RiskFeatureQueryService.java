package com.example.zongshe1.modules.risk.feature;

import com.example.zongshe1.common.dto.FeatureSnapshotDTO;
import com.example.zongshe1.modules.user.entity.User;
import com.example.zongshe1.modules.crawler.service.ExternalDataCacheService;
import com.example.zongshe1.modules.user.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class RiskFeatureQueryService {

    private final UserRepository userRepository;
    private final ExternalDataCacheService cacheService;


    public FeatureSnapshotDTO query(Long userId) {

        User user = userRepository.findById(userId)
                .orElseThrow(() ->
                        new RuntimeException("用户不存在")
                );


        return query(user);
    }

    public FeatureSnapshotDTO query(User user) {
        FeatureSnapshotDTO dto = new FeatureSnapshotDTO();

        dto.setUserId(user.getId());
        dto.setCreditScore(user.getCreditScore());


        // ==========================
        // 失信查询
        // ==========================

        String name = normalizeName(user.getUserName());

        String lookupKey = "NAME_" + name;


        Map<String,Object> dishonest =
                cacheService
                        .getExactPayload(
                                "DISHONEST_PUBLIC",
                                lookupKey
                        )
                        .orElse(null);


        if (dishonest != null) {

            dto.setDishonestHit(true);

            dto.setDishonestName(
                    (String) dishonest.get("dishonestName")
            );

            dto.setCourtName(
                    (String) dishonest.get("courtName")
            );

            dto.setPublishDate(
                    parseDate(
                            dishonest.get("publishDate")
                    )
            );

        } else {

            dto.setDishonestHit(false);

        }


        // ==========================
        // LPR查询
        // ==========================


        Map<String,Object> lpr =
                cacheService
                        .getLatestPayload(
                                "MACRO_LPR",
                                "LPR_CURRENT"
                        )
                        .orElse(null);


        if(lpr != null){

            dto.setLpr1y(
                    toDecimal(lpr.get("lpr1y"))
            );

            dto.setLpr5y(
                    toDecimal(lpr.get("lpr5y"))
            );

            dto.setLprPublishDate(
                    parseDate(
                            lpr.get("lprPublishDate")
                    )
            );

            dto.setStale(cacheService.getExactPayload("MACRO_LPR", "LPR_CURRENT").isEmpty());

        }else{

            dto.setStale(true);

        }


        return dto;
    }



    private String normalizeName(String name){

        if(name == null){
            return "";
        }

        return name
                .trim()
                .replace(" ","")
                .replace("　","");
    }



    private BigDecimal toDecimal(Object value){

        if(value == null){
            return null;
        }

        return new BigDecimal(
                value.toString()
        );
    }



    private LocalDateTime parseDate(Object value){

        if(value == null){
            return null;
        }

        try{
            return LocalDateTime.parse(
                    value.toString()
            );
        }catch(Exception e){

            return null;
        }
    }

}