package com.example.zongshe1.modules.product.service;

import com.example.zongshe1.modules.product.dto.ProductDTO;

import java.util.List;

public interface ProductService {

    List<ProductDTO> getProducts(String type, String status, String search);

    ProductDTO getProductById(Long id);

    ProductDTO createProduct(ProductDTO productDTO);

    ProductDTO updateProduct(Long id, ProductDTO productDTO);

    boolean deleteProduct(Long id);
}