# FRESH API Contract (v1)

## Base URL
`https://api.fresh-market.sa/api/v1/`

## Endpoints

### 1. Categories
`GET /categories/`
Response: `List<CategoryDto>`

### 2. Products
`GET /products/`
Response: `List<ProductDto>` (Paginated)

`GET /products/{id}/`
Response: `ProductDto`

### 3. Orders
`POST /orders/`
Request: `OrderRequest`
Response: `OrderResponse` (Status: 201 Created)

`GET /orders/{id}/`
Response: `OrderResponse`

## Error Format
```json
{
  "code": "ERROR_CODE",
  "message": "Human readable error message"
}
```
