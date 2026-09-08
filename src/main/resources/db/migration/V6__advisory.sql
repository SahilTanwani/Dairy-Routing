-- V6 — advisory.
--
-- The consolidation advisory: "these four points in Rampur are within 400 m of each other;
-- merging them into one saves 11 minutes a session and nobody walks more than 380 m."
--
-- Proposals are generated and ranked, never executed. Merging collection points is a
-- decision about people's mornings, so it belongs to the cooperative, not the solver. The
-- status column tracks that human decision; only IMPLEMENTED writes back to
-- collection_point.merged_into_id.

CREATE TABLE merge_proposal (
    id               BIGSERIAL PRIMARY KEY,
    village_id       BIGINT NOT NULL REFERENCES village(id),
    -- The surviving point. The others in merge_proposal_point fold into it.
    hub_point_id     BIGINT NOT NULL REFERENCES collection_point(id),
    minutes_saved    NUMERIC(6,2) NOT NULL,
    farmers_affected INT NOT NULL,
    -- The number that decides whether the proposal is humane: the worst walk anyone in the
    -- merge would face, checked against the maxWalkMetres solver parameter.
    max_walk_metres  INT NOT NULL,
    litres_affected  NUMERIC(7,2) NOT NULL,
    confidence       VARCHAR(10) NOT NULL,
    status           VARCHAR(16) NOT NULL DEFAULT 'PROPOSED',
    generated_at     TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_mp_status CHECK (status IN
        ('PROPOSED','ACCEPTED','REJECTED','IMPLEMENTED'))
);

-- The points folding into the hub, each with the walk it imposes. Composite primary key:
-- a point appears at most once in a proposal, and there is no surrogate id worth having.
CREATE TABLE merge_proposal_point (
    proposal_id         BIGINT NOT NULL REFERENCES merge_proposal(id) ON DELETE CASCADE,
    collection_point_id BIGINT NOT NULL REFERENCES collection_point(id),
    walk_metres         INT    NOT NULL,
    PRIMARY KEY (proposal_id, collection_point_id)
);
