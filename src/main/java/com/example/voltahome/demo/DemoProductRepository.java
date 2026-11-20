package com.example.voltahome.demo;

import java.util.List;
import java.util.Optional;

import org.springframework.data.repository.Repository;

/** DEMO ONLY. The catalogue, read only. */
interface DemoProductRepository extends Repository<DemoProduct, String> {

    Optional<DemoProduct> findByProductCode(String productCode);

    List<DemoProduct> findAllByOrderByProductCode();
}
