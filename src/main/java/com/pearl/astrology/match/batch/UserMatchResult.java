package com.pearl.astrology.match.batch;

import com.pearl.astrology.match.entity.Match;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class UserMatchResult {
    private String userId;
    private List<Match> matches;
}
